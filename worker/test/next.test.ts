import { env, fetchMock, SELF } from "cloudflare:test";
import { beforeAll, afterEach, describe, expect, it } from "vitest";
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

describe("GET /next", () => {
  it("returns up to 3 compact trips for dir=ab, using the recorded NS fixture", async () => {
    mockNsTrips(env.STATION_A, env.STATION_B, fixture);

    const response = await SELF.fetch("https://worker.example/next?dir=ab");
    expect(response.status).toBe(200);

    const json = await response.json<{ dir: string; trips: unknown[] }>();
    expect(json.dir).toBe("ab");
    expect(json.trips).toHaveLength(3);

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

  it("swaps stations for dir=ba", async () => {
    mockNsTrips(env.STATION_B, env.STATION_A, { source: "TEST", trips: [] });

    const response = await SELF.fetch("https://worker.example/next?dir=ba");
    expect(response.status).toBe(200);
    const json = await response.json<{ dir: string; trips: unknown[] }>();
    expect(json.dir).toBe("ba");
    expect(json.trips).toEqual([]);
  });

  it("rejects an invalid dir param", async () => {
    const response = await SELF.fetch("https://worker.example/next?dir=xx");
    expect(response.status).toBe(400);
    const json = await response.json<{ error: { code: string } }>();
    expect(json.error.code).toBe("INVALID_DIRECTION");
  });

  it("returns 502 with a machine-readable body when the NS API fails", async () => {
    mockNsTrips(env.STATION_A, env.STATION_B, { message: "boom" }, 500);

    const response = await SELF.fetch("https://worker.example/next?dir=ab");
    expect(response.status).toBe(502);
    const json = await response.json<{ error: { code: string; message: string } }>();
    expect(json.error.code).toBe("NS_API_UNAVAILABLE");
    expect(typeof json.error.message).toBe("string");
  });
});
