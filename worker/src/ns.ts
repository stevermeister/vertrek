import type { NsLeg, NsTrip, NsTripsResponse } from "./ns-types";

export interface Env {
  STATION_A: string;
  STATION_B: string;
  NS_API_KEY: string;
}

export interface CompactTrip {
  departureTime: string;
  delayMinutes: number;
  track: string | null;
  durationMinutes: number;
  transfers: number;
  cancelled: boolean;
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

export async function fetchTrips(
  env: Env,
  fromStation: string,
  toStation: string,
): Promise<NsTripsResponse> {
  const url = new URL(NS_TRIPS_URL);
  url.searchParams.set("fromStation", fromStation);
  url.searchParams.set("toStation", toStation);

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

function toCompactTrip(trip: NsTrip): CompactTrip {
  const firstLeg = trip.legs[0] as NsLeg | undefined;
  const origin = firstLeg?.origin;

  const plannedTime = origin?.plannedDateTime;
  const actualTime = origin?.actualDateTime ?? plannedTime;

  return {
    departureTime: actualTime ?? new Date(0).toISOString(),
    delayMinutes: computeDelayMinutes(plannedTime, origin?.actualDateTime),
    track: origin?.actualTrack ?? origin?.plannedTrack ?? null,
    durationMinutes: Math.round(
      trip.actualDurationInMinutes ?? trip.plannedDurationInMinutes,
    ),
    transfers: trip.transfers,
    cancelled: trip.status === "CANCELLED" || trip.legs.some((l) => l.cancelled),
  };
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
