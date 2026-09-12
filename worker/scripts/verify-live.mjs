#!/usr/bin/env node
/**
 * Manual, opt-in check that the live NS reisinformatie-api v3 /trips
 * response still matches the shape assumed in src/ns-types.ts.
 *
 * This is NOT run in CI (it needs a real NS_API_KEY) — run it yourself
 * whenever you want to sanity-check the assumed schema against reality:
 *
 *   NS_API_KEY=xxxx FROM_STATION=ASD TO_STATION=UT npm run verify:live
 *
 * Safety rules for this script (do not weaken these):
 *   - Never log the API key, in any form.
 *   - Never log request headers.
 *   - Never log response bodies or field values (they're live train data
 *     for whatever route you ask about — treat it as data, not something
 *     to print). Only field NAMES (paths) are ever logged.
 */

const NS_TRIPS_URL = "https://gateway.apiportal.ns.nl/reisinformatie-api/api/v3/trips";

const REQUIRED_RESPONSE_FIELDS = ["trips"];
const KNOWN_RESPONSE_FIELDS = [...REQUIRED_RESPONSE_FIELDS, "source"];

const REQUIRED_TRIP_FIELDS = [
  "plannedDurationInMinutes",
  "actualDurationInMinutes",
  "transfers",
  "status",
  "legs",
];
const KNOWN_TRIP_FIELDS = [...REQUIRED_TRIP_FIELDS, "uid", "optimal", "crowdForecast"];

const REQUIRED_LEG_FIELDS = ["cancelled", "origin", "destination"];
const KNOWN_LEG_FIELDS = [...REQUIRED_LEG_FIELDS, "idx", "name", "direction", "product"];

const REQUIRED_STOP_FIELDS = ["plannedDateTime"];
const KNOWN_STOP_FIELDS = [...REQUIRED_STOP_FIELDS, "actualDateTime", "plannedTrack", "actualTrack", "name"];

function checkShape(obj, known, required, path, missing, unexpected) {
  if (typeof obj !== "object" || obj === null) {
    missing.push(`${path} (expected an object, got ${typeof obj})`);
    return;
  }
  for (const field of required) {
    if (!(field in obj)) missing.push(`${path}.${field}`);
  }
  for (const key of Object.keys(obj)) {
    if (!known.includes(key)) unexpected.push(`${path}.${key}`);
  }
}

function main() {
  const apiKey = process.env.NS_API_KEY;
  const fromStation = process.env.FROM_STATION ?? "ASD";
  const toStation = process.env.TO_STATION ?? "UT";

  if (!apiKey) {
    console.error(
      "NS_API_KEY env var is required (never hardcode it). Example:\n" +
        "  NS_API_KEY=xxxx npm run verify:live",
    );
    process.exitCode = 1;
    return Promise.resolve();
  }

  const maxTrips = Number(process.env.MAX_TRIPS ?? "6");

  const url = new URL(NS_TRIPS_URL);
  url.searchParams.set("fromStation", fromStation);
  url.searchParams.set("toStation", toStation);
  url.searchParams.set("previousAdvices", "0");
  url.searchParams.set("nextAdvices", String(maxTrips));

  console.log(
    `Checking live schema: GET ${url.pathname}?fromStation=${fromStation}&toStation=${toStation}` +
      `&previousAdvices=0&nextAdvices=${maxTrips}`,
  );

  return fetch(url.toString(), {
    headers: {
      "Ocp-Apim-Subscription-Key": apiKey,
      Accept: "application/json",
    },
  })
    .then(async (response) => {
      if (!response.ok) {
        // Deliberately no body/headers logged — could echo back request details.
        console.error(`NS API responded with HTTP ${response.status} ${response.statusText}`);
        process.exitCode = 1;
        return;
      }

      const body = await response.json();
      const missing = [];
      const unexpected = [];

      checkShape(body, KNOWN_RESPONSE_FIELDS, REQUIRED_RESPONSE_FIELDS, "$", missing, unexpected);

      const trips = Array.isArray(body?.trips) ? body.trips : [];
      trips.forEach((trip, tripIdx) => {
        checkShape(trip, KNOWN_TRIP_FIELDS, REQUIRED_TRIP_FIELDS, `$.trips[${tripIdx}]`, missing, unexpected);
        const legs = Array.isArray(trip?.legs) ? trip.legs : [];
        legs.forEach((leg, legIdx) => {
          const legPath = `$.trips[${tripIdx}].legs[${legIdx}]`;
          checkShape(leg, KNOWN_LEG_FIELDS, REQUIRED_LEG_FIELDS, legPath, missing, unexpected);
          for (const stopKey of ["origin", "destination"]) {
            if (leg && typeof leg === "object" && stopKey in leg) {
              checkShape(
                leg[stopKey],
                KNOWN_STOP_FIELDS,
                REQUIRED_STOP_FIELDS,
                `${legPath}.${stopKey}`,
                missing,
                unexpected,
              );
            }
          }
        });
      });

      console.log(`Trips returned: ${trips.length} (requested nextAdvices=${maxTrips})`);
      if (trips.length < maxTrips) {
        console.log(
          `Fewer trips than requested — could be genuinely no more trains soon, or NS's ` +
            `nextAdvices may not behave as documented (see the comment above fetchTrips() in ` +
            `src/ns.ts). Re-run at a busier time of day before assuming the latter.`,
        );
      }

      if (missing.length === 0) {
        console.log("No required fields missing — src/ns-types.ts still matches.");
      } else {
        console.log("MISSING required fields (breaking — update src/ns-types.ts and src/ns.ts):");
        for (const path of dedupe(missing)) console.log(`  - ${path}`);
      }

      if (unexpected.length > 0) {
        console.log("New/unrecognised fields (informational only, nothing broke):");
        for (const path of dedupe(unexpected)) console.log(`  - ${path}`);
      }

      if (missing.length > 0) process.exitCode = 1;
    })
    .catch((err) => {
      // err.message could theoretically embed the URL (no key in it) but never headers/key.
      console.error(`Network error contacting NS API: ${err.message}`);
      process.exitCode = 1;
    });
}

function dedupe(list) {
  return [...new Set(list)];
}

main();
