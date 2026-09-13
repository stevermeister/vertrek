import { checkVertrekKey } from "./auth";
import { extractStationNames, fetchTrips, NsApiError, toCompactTrips, type Env } from "./ns";

const DEFAULT_MAX_TRIPS = 5;
const CACHE_TTL_SECONDS = 30;

type Direction = "ab" | "ba";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname !== "/next") {
      return jsonError(404, "NOT_FOUND", "Unknown path. Use GET /next?dir=ab|ba.");
    }

    // Auth first: no cache lookup, no upstream call, for any rejection.
    const auth = checkVertrekKey(request, env);
    if (!auth.ok) {
      return jsonError(auth.status, auth.code, auth.message);
    }

    if (request.method !== "GET") {
      return jsonError(405, "METHOD_NOT_ALLOWED", "Only GET is supported.");
    }

    const dirParam = url.searchParams.get("dir");
    if (dirParam !== "ab" && dirParam !== "ba") {
      return jsonError(
        400,
        "INVALID_DIRECTION",
        'Query param "dir" must be "ab" or "ba".',
      );
    }
    const dir: Direction = dirParam;

    const cache = caches.default;
    const cacheKey = new Request(`${url.origin}/next?dir=${dir}`, request);

    const cached = await cache.match(cacheKey);
    if (cached) {
      return cached;
    }

    const { from, to } = resolveStations(env, dir);
    const maxTrips = resolveMaxTrips(env);

    let compactTrips;
    let stationNames;
    try {
      const nsResponse = await fetchTrips(env, from, to);
      compactTrips = toCompactTrips(nsResponse, maxTrips);
      stationNames = extractStationNames(nsResponse, from, to);
    } catch (err) {
      if (err instanceof NsApiError) {
        return jsonError(502, "NS_API_UNAVAILABLE", err.message, {
          upstreamStatus: err.upstreamStatus,
        });
      }
      return jsonError(502, "NS_API_UNAVAILABLE", (err as Error).message);
    }

    const response = new Response(
      JSON.stringify({
        dir,
        fromStationName: stationNames.fromStationName,
        toStationName: stationNames.toStationName,
        trips: compactTrips,
      }),
      {
        status: 200,
        headers: {
          "content-type": "application/json",
          "cache-control": `public, max-age=${CACHE_TTL_SECONDS}`,
        },
      },
    );

    await cache.put(cacheKey, response.clone());
    return response;
  },
} satisfies ExportedHandler<Env>;

function resolveMaxTrips(env: Env): number {
  const parsed = Number(env.MAX_TRIPS);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : DEFAULT_MAX_TRIPS;
}

function resolveStations(env: Env, dir: Direction): { from: string; to: string } {
  return dir === "ab"
    ? { from: env.STATION_A, to: env.STATION_B }
    : { from: env.STATION_B, to: env.STATION_A };
}

function jsonError(
  status: number,
  code: string,
  message: string,
  extra?: Record<string, unknown>,
): Response {
  return new Response(
    JSON.stringify({ error: { code, message, ...extra } }),
    {
      status,
      headers: { "content-type": "application/json" },
    },
  );
}
