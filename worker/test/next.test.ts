import { env, fetchMock, SELF } from "cloudflare:test";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import worker from "../src/index";
import { VERTREK_KEY_HEADER } from "../src/auth";
import fixture from "./fixtures/trips-response.json";

beforeAll(() => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
});

afterEach(() => {
  fetchMock.assertNoPendingInterceptors();
});

function mockNsTrips(fromStation: string, toStation: string, body: unknown, status = 200) {
  fetchMock
    .get("https://gateway.apiportal.ns.nl")
    .intercept({
      path: (path: string) =>
        path.startsWith("/reisinformatie-api/api/v3/trips") &&
        path.includes(`fromStation=${fromStation}`) &&
        path.includes(`toStation=${toStation}`),
    })
    .reply(status, JSON.stringify(body), {
      headers: { "content-type": "application/json" },
    });
}

function authedFetch(url: string, init: RequestInit = {}) {
  return SELF.fetch(url, {
    ...init,
    headers: { ...init.headers, [VERTREK_KEY_HEADER]: env.VERTREK_KEY! },
  });
}

describe("GET /next auth", () => {
  it("rejects a request with no X-Vertrek-Key header, without calling the NS API", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");

    const response = await SELF.fetch("https://worker.example/next?dir=ab");

    expect(response.status).toBe(401);
    const json = await response.json<{ error: { code: string } }>();
    expect(json.error.code).toBe("UNAUTHORIZED");
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });

  it("rejects a request with an empty X-Vertrek-Key header, without calling the NS API", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");

    const response = await SELF.fetch("https://worker.example/next?dir=ab", {
      headers: { [VERTREK_KEY_HEADER]: "" },
    });

    expect(response.status).toBe(401);
    const json = await response.json<{ error: { code: string } }>();
    expect(json.error.code).toBe("UNAUTHORIZED");
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });

  it("rejects a request with the wrong key, without calling the NS API", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");

    const response = await SELF.fetch("https://worker.example/next?dir=ab", {
      headers: { [VERTREK_KEY_HEADER]: "definitely-not-the-key" },
    });

    expect(response.status).toBe(401);
    const json = await response.json<{ error: { code: string } }>();
    expect(json.error.code).toBe("UNAUTHORIZED");
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });

  it("accepts a request with the correct key", async () => {
    mockNsTrips(env.STATION_A, env.STATION_B, { source: "TEST", trips: [] });

    const response = await authedFetch("https://worker.example/next?dir=ab");

    expect(response.status).toBe(200);
  });

  it("fails closed with 500 when VERTREK_KEY is not configured, without calling the NS API", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");

    const request = new Request("https://worker.example/next?dir=ab", {
      headers: { [VERTREK_KEY_HEADER]: "irrelevant-because-secret-is-unset" },
    });
    const unconfiguredEnv = { ...env, VERTREK_KEY: undefined };

    const response = await worker.fetch(request, unconfiguredEnv);

    expect(response.status).toBe(500);
    const json = await response.json<{ error: { code: string } }>();
    expect(json.error.code).toBe("SERVER_MISCONFIGURED");
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });
});

describe("GET /next", () => {
  it("returns up to 5 compact trips for dir=ab, using the recorded NS fixture", async () => {
    mockNsTrips(env.STATION_A, env.STATION_B, fixture);

    const response = await authedFetch("https://worker.example/next?dir=ab");
    expect(response.status).toBe(200);

    const json = await response.json<{ dir: string; trips: unknown[] }>();
    expect(json.dir).toBe("ab");
    // The fixture has 7 trips — this proves the cap still trims, not just that 5 fit.
    expect(json.trips).toHaveLength(5);

    const [first, second] = json.trips as Array<Record<string, unknown>>;

    expect(first).toEqual({
      departureTime: "2026-11-02T12:08:00+0100",
      delayMinutes: 5,
      track: "4b",
      durationMinutes: 33,
      transfers: 0,
      cancelled: false,
    });

    expect(second).toEqual({
      departureTime: "2026-11-02T12:18:00+0100",
      delayMinutes: 0,
      track: "4b",
      durationMinutes: 28,
      transfers: 0,
      cancelled: true,
    });
  });

  it("asks NS for previousAdvices=0 and does NOT send nextAdvices (confirmed decommissioned live)", async () => {
    let capturedPath: string | undefined;
    fetchMock
      .get("https://gateway.apiportal.ns.nl")
      .intercept({
        path: (path: string) => {
          if (!path.startsWith("/reisinformatie-api/api/v3/trips")) return false;
          capturedPath = path;
          return true;
        },
      })
      .reply(200, JSON.stringify({ source: "TEST", trips: [] }), {
        headers: { "content-type": "application/json" },
      });

    await authedFetch("https://worker.example/next?dir=ab");

    expect(capturedPath).toContain("previousAdvices=0");
    expect(capturedPath).not.toContain("nextAdvices");
  });

  it("swaps stations for dir=ba", async () => {
    mockNsTrips(env.STATION_B, env.STATION_A, { source: "TEST", trips: [] });

    const response = await authedFetch("https://worker.example/next?dir=ba");
    expect(response.status).toBe(200);
    const json = await response.json<{ dir: string; trips: unknown[] }>();
    expect(json.dir).toBe("ba");
    expect(json.trips).toEqual([]);
  });

  it("rejects an invalid dir param", async () => {
    const response = await authedFetch("https://worker.example/next?dir=xx");
    expect(response.status).toBe(400);
    const json = await response.json<{ error: { code: string } }>();
    expect(json.error.code).toBe("INVALID_DIRECTION");
  });

  it("returns 502 with a machine-readable body when the NS API fails", async () => {
    mockNsTrips(env.STATION_A, env.STATION_B, { message: "boom" }, 500);

    const response = await authedFetch("https://worker.example/next?dir=ab");
    expect(response.status).toBe(502);
    const json = await response.json<{ error: { code: string; message: string } }>();
    expect(json.error.code).toBe("NS_API_UNAVAILABLE");
    expect(typeof json.error.message).toBe("string");
  });
});
