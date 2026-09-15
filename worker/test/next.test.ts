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
  it("returns up to 4 compact trips for dir=ab, using the recorded NS fixture", async () => {
    mockNsTrips(env.STATION_A, env.STATION_B, fixture);

    const response = await authedFetch("https://worker.example/next?dir=ab");
    expect(response.status).toBe(200);

    const json = await response.json<{
      dir: string;
      fromStationName: string;
      toStationName: string;
      fromStationShort: string;
      toStationShort: string;
      trips: unknown[];
    }>();
    expect(json.dir).toBe("ab");
    // Read from the fixture's own legs, not hardcoded — proves these come
    // from the NS response, not from any Worker-side station config.
    expect(json.fromStationName).toBe("Amsterdam Centraal");
    expect(json.toStationName).toBe("Utrecht Centraal");
    // Test env has STATION_A_SHORT/STATION_B_SHORT set — see vitest.config.ts.
    expect(json.fromStationShort).toBe(env.STATION_A_SHORT);
    expect(json.toStationShort).toBe(env.STATION_B_SHORT);
    // The fixture has 7 trips — this proves the cap still trims, not just that 4 fit.
    expect(json.trips).toHaveLength(4);

    const [first, second] = json.trips as Array<Record<string, unknown>>;

    expect(first).toEqual({
      // Planned, not actual (12:08) — see the comment on toCompactTrip()
      // in ns.ts. The client renders this with delayMinutes as a separate
      // marker; sending the adjusted time here would double-count it.
      departureTime: "2026-11-02T12:03:00+0100",
      arrivalTime: "2026-11-02T12:36:00+0100",
      delayMinutes: 5,
      track: "4b",
      cancelled: false,
      crowdForecast: "MEDIUM",
    });

    expect(second).toEqual({
      departureTime: "2026-11-02T12:18:00+0100",
      arrivalTime: "2026-11-02T12:46:00+0100",
      delayMinutes: 0,
      track: "4b",
      cancelled: true,
      crowdForecast: "UNKNOWN",
    });
  });

  it("reports the planned departure time for a delayed trip, not the actual/adjusted one", async () => {
    mockNsTrips(env.STATION_A, env.STATION_B, fixture);

    const response = await authedFetch("https://worker.example/next?dir=ab");
    const json = await response.json<{ trips: Array<{ departureTime: string; delayMinutes: number }> }>();

    // trip-1: planned 12:03, actual 12:08 (5 min delay). The response must
    // carry the planned time — the client adds the delay marker itself.
    expect(json.trips[0]?.departureTime).toBe("2026-11-02T12:03:00+0100");
    expect(json.trips[0]?.delayMinutes).toBe(5);
  });

  it("reduces a multi-leg trip's crowdForecast to its busiest leg", async () => {
    mockNsTrips(env.STATION_A, env.STATION_B, fixture);

    const response = await authedFetch("https://worker.example/next?dir=ab");
    const json = await response.json<{ trips: Array<{ crowdForecast: string }> }>();

    // trip-3 (index 2): leg 0 is LOW, leg 1 is HIGH -> busiest is HIGH.
    expect(json.trips[2]?.crowdForecast).toBe("HIGH");
  });

  it("maps a missing crowdForecast field to UNKNOWN", async () => {
    mockNsTrips(env.STATION_A, env.STATION_B, fixture);

    const response = await authedFetch("https://worker.example/next?dir=ab");
    const json = await response.json<{ trips: Array<{ crowdForecast: string }> }>();

    // trip-2 (index 1, cancelled) has no crowdForecast field on its only leg.
    expect(json.trips[1]?.crowdForecast).toBe("UNKNOWN");
  });

  it("maps an unrecognised crowdForecast value to UNKNOWN rather than passing it through", async () => {
    mockNsTrips(env.STATION_A, env.STATION_B, fixture);

    const response = await authedFetch("https://worker.example/next?dir=ab");
    const json = await response.json<{ trips: Array<{ crowdForecast: string }> }>();

    // trip-4 (index 3, the last trip inside the 4-trip cap) carries
    // "UNRECOGNIZED_FUTURE_VALUE" on its leg. It must land here, not on
    // trip-5/6/7 which the cap excludes entirely — otherwise this test
    // would pass without ever exercising the defensive-parsing branch.
    expect(json.trips[3]?.crowdForecast).toBe("UNKNOWN");
  });

  it("falls back to the requested station codes as names when there are no trips to read names from", async () => {
    mockNsTrips(env.STATION_A, env.STATION_B, { source: "TEST", trips: [] });

    const response = await authedFetch("https://worker.example/next?dir=ab");
    const json = await response.json<{ fromStationName: string; toStationName: string }>();

    expect(json.fromStationName).toBe(env.STATION_A);
    expect(json.toStationName).toBe(env.STATION_B);
  });

  it("swaps the short station names for dir=ba, same as the full names", async () => {
    mockNsTrips(env.STATION_B, env.STATION_A, { source: "TEST", trips: [] });

    const response = await authedFetch("https://worker.example/next?dir=ba");
    const json = await response.json<{ fromStationShort: string; toStationShort: string }>();

    expect(json.fromStationShort).toBe(env.STATION_B_SHORT);
    expect(json.toStationShort).toBe(env.STATION_A_SHORT);
  });

  it("falls back to the full station name when a _SHORT var is unset", async () => {
    mockNsTrips(env.STATION_A, env.STATION_B, fixture);

    const unconfiguredEnv = { ...env, STATION_A_SHORT: undefined, STATION_B_SHORT: undefined };
    const request = new Request("https://worker.example/next?dir=ab", {
      headers: { [VERTREK_KEY_HEADER]: env.VERTREK_KEY! },
    });
    const response = await worker.fetch(request, unconfiguredEnv);
    const json = await response.json<{
      fromStationName: string;
      toStationName: string;
      fromStationShort: string;
      toStationShort: string;
    }>();

    expect(json.fromStationShort).toBe(json.fromStationName);
    expect(json.toStationShort).toBe(json.toStationName);
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
