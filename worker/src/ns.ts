import type { NsLeg, NsTrip, NsTripsResponse } from "./ns-types";

export interface Env {
  STATION_A: string;
  STATION_B: string;
  // Optional short display names for the tile header (e.g. "Amsterdam"
  // instead of "Amsterdam Centraal"). Passed through verbatim, no
  // truncation logic — see index.ts's resolveShortStationNames(), which
  // falls back to the full station name when unset.
  STATION_A_SHORT?: string;
  STATION_B_SHORT?: string;
  NS_API_KEY: string;
  // Shared secret the watch app sends as X-Vertrek-Key. Optional in the
  // type because an operator can forget to set it — see src/auth.ts,
  // which fails closed (500) rather than treating that as "no auth".
  VERTREK_KEY?: string;
  // How many upcoming trips to return. Wrangler vars are always strings;
  // parsed with a default in index.ts's resolveMaxTrips().
  MAX_TRIPS?: string;
}

export type CrowdForecast = "LOW" | "MEDIUM" | "HIGH" | "UNKNOWN";

export interface CompactTrip {
  departureTime: string;
  arrivalTime: string;
  delayMinutes: number;
  track: string | null;
  cancelled: boolean;
  crowdForecast: CrowdForecast;
  // 0 means a direct connection, no transfers. The tile prefers direct
  // trips for its 2-row view; MainActivity shows everything unfiltered.
  transfers: number;
}

export class NsApiError extends Error {
  constructor(
    message: string,
    public readonly upstreamStatus?: number,
  ) {
    super(message);
    this.name = "NsApiError";
  }
}

const NS_TRIPS_URL =
  "https://gateway.apiportal.ns.nl/reisinformatie-api/api/v3/trips";

// NOTE: 5 trips per call is an NS-side cap, not a choice we make here.
// Verified live against a real subscription key (2026-09-13): nextAdvices
// and previousAdvices are confirmed decommissioned on this endpoint —
// every tested value from 0 to 20 for either param still returned exactly
// 5 trips. Neither is sent below; sending nextAdvices would just be noise.
//
// The response does carry a real, working pagination mechanism —
// scrollRequestForwardContext — which a second call could pass back as a
// `context` param to fetch more trips with zero overlap. We deliberately
// did not implement this: it would double NS API calls (and latency, and
// failure surface) per /next request just to get more than 5 trips. If
// that trade-off ever looks worth it, that's where to start.
export async function fetchTrips(
  env: Env,
  fromStation: string,
  toStation: string,
): Promise<NsTripsResponse> {
  const url = new URL(NS_TRIPS_URL);
  url.searchParams.set("fromStation", fromStation);
  url.searchParams.set("toStation", toStation);
  // We only want upcoming trips, never ones before the search time.
  url.searchParams.set("previousAdvices", "0");

  let response: Response;
  try {
    response = await fetch(url.toString(), {
      headers: {
        "Ocp-Apim-Subscription-Key": env.NS_API_KEY,
        Accept: "application/json",
      },
    });
  } catch (err) {
    throw new NsApiError(
      `Network error contacting NS API: ${(err as Error).message}`,
    );
  }

  if (!response.ok) {
    throw new NsApiError(
      `NS API responded with status ${response.status}`,
      response.status,
    );
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch (err) {
    throw new NsApiError(
      `NS API returned invalid JSON: ${(err as Error).message}`,
    );
  }

  if (!isNsTripsResponse(body)) {
    throw new NsApiError("NS API returned an unexpected response shape");
  }

  return body;
}

function isNsTripsResponse(value: unknown): value is NsTripsResponse {
  return (
    typeof value === "object" &&
    value !== null &&
    Array.isArray((value as { trips?: unknown }).trips)
  );
}

export function toCompactTrips(
  response: NsTripsResponse,
  limit: number,
): CompactTrip[] {
  return response.trips.slice(0, limit).map(toCompactTrip);
}

function isCancelled(trip: NsTrip): boolean {
  return trip.status === "CANCELLED" || trip.legs.some((l) => l.cancelled);
}

/**
 * Full display names for the header (e.g. "Almere Oostvaarders"), read
 * from the NS response itself rather than hardcoded anywhere — the
 * Worker only ever configures station *codes*. Falls back to the codes
 * we queried with if there are no trips to read names from (e.g. no
 * service running right now); a code is a degraded but honest display
 * name, never a crash.
 */
export function extractStationNames(
  response: NsTripsResponse,
  fallbackFromCode: string,
  fallbackToCode: string,
): { fromStationName: string; toStationName: string } {
  const firstTrip = response.trips[0];
  const firstLeg = firstTrip?.legs[0] as NsLeg | undefined;
  const lastLeg = firstTrip?.legs[firstTrip.legs.length - 1] as NsLeg | undefined;

  return {
    fromStationName: firstLeg?.origin.name ?? fallbackFromCode,
    toStationName: lastLeg?.destination.name ?? fallbackToCode,
  };
}

function toCompactTrip(trip: NsTrip): CompactTrip {
  const firstLeg = trip.legs[0] as NsLeg | undefined;
  const lastLeg = trip.legs[trip.legs.length - 1] as NsLeg | undefined;
  const origin = firstLeg?.origin;
  const destination = lastLeg?.destination;

  const plannedDeparture = origin?.plannedDateTime;
  const actualArrival = destination?.actualDateTime ?? destination?.plannedDateTime;

  return {
    // Planned, not actual: the client renders this with delayMinutes as a
    // separate "+N" marker (as the NS app does). Sending the already-
    // adjusted actual time here would double-count the delay on screen —
    // e.g. planned 12:03 + 5 min actual delay would show "12:08 +5",
    // implying a further 5-minute slip on top of an already-late time.
    departureTime: plannedDeparture ?? new Date(0).toISOString(),
    arrivalTime: actualArrival ?? new Date(0).toISOString(),
    delayMinutes: computeDelayMinutes(plannedDeparture, origin?.actualDateTime),
    track: origin?.actualTrack ?? origin?.plannedTrack ?? null,
    cancelled: isCancelled(trip),
    crowdForecast: reduceCrowdForecast(trip.legs),
    transfers: trip.transfers,
  };
}

const CROWD_SEVERITY: Record<CrowdForecast, number> = {
  UNKNOWN: 0,
  LOW: 1,
  MEDIUM: 2,
  HIGH: 3,
};

function parseCrowdForecast(value: string | undefined): CrowdForecast {
  return value === "LOW" || value === "MEDIUM" || value === "HIGH" ? value : "UNKNOWN";
}

/**
 * crowdForecast lives on each leg, not the trip (NsTrip.crowdForecast
 * exists too, verified live, but we deliberately don't trust it — it's
 * undocumented whether it's NS's own rollup of the same legs or something
 * else, and reisinformatie-api's OpenAPI spec isn't reachable to check).
 * For a multi-leg trip we report the single busiest leg: a traveler cares
 * about the most crowded segment of their journey, not an average.
 */
function reduceCrowdForecast(legs: NsLeg[]): CrowdForecast {
  let busiest: CrowdForecast = "UNKNOWN";
  for (const leg of legs) {
    const forecast = parseCrowdForecast(leg.crowdForecast);
    if (CROWD_SEVERITY[forecast] > CROWD_SEVERITY[busiest]) {
      busiest = forecast;
    }
  }
  return busiest;
}

function computeDelayMinutes(
  plannedIso: string | undefined,
  actualIso: string | undefined,
): number {
  if (!plannedIso || !actualIso) return 0;
  const plannedMs = Date.parse(plannedIso);
  const actualMs = Date.parse(actualIso);
  if (Number.isNaN(plannedMs) || Number.isNaN(actualMs)) return 0;
  return Math.max(0, Math.round((actualMs - plannedMs) / 60_000));
}
