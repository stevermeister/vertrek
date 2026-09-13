/**
 * Shape of the NS reisinformatie-api v3 `/trips` response.
 *
 * The official OpenAPI spec lives behind an authenticated session on
 * apiportal.ns.nl (an Azure API Management developer portal — the schema
 * is not exposed to anonymous fetches). This shape was cross-verified
 * against two independent real-world clients built against the live v3
 * API (a TypeScript MCP server and the long-standing aquatix/ns-api
 * Python wrapper), which agree exactly on these field names. Re-verify
 * against the real spec (apiportal.ns.nl -> reisinformatie-api -> API
 * Details -> trips) if NS changes this endpoint.
 */

export interface NsTripsResponse {
  source: string;
  trips: NsTrip[];
}

export interface NsTrip {
  uid: string;
  plannedDurationInMinutes: number;
  actualDurationInMinutes?: number;
  transfers: number;
  status: string; // e.g. "NORMAL" | "CANCELLED" | "REPLACED" | "NOT_OPTIMAL" | "PARTIALLY_CANCELLED"
  optimal: boolean;
  crowdForecast?: string;
  legs: NsLeg[];
}

export interface NsLeg {
  idx: string;
  name: string;
  cancelled: boolean;
  direction?: string;
  origin: NsStopInfo;
  destination: NsStopInfo;
  product: {
    displayName: string;
    type: string;
    number: string;
    operatorName: string;
  };
  // Verified live (2026-09-13): crowdForecast is a string enum ("LOW" seen;
  // "MEDIUM"/"HIGH" documented by NS, not observed live) and lives on the
  // leg, not just the trip (NsTrip.crowdForecast above also exists but we
  // deliberately don't use it — see the comment above reduceCrowdForecast
  // in src/ns.ts).
  crowdForecast?: string;
}

export interface NsStopInfo {
  name: string;
  plannedDateTime: string;
  actualDateTime?: string;
  plannedTrack?: string;
  actualTrack?: string;
}
