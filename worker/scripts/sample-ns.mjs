#!/usr/bin/env node
/**
 * Manual, opt-in measurement of how often the live NS schedule has
 * *fewer than 2 direct (transfers == 0) trains* in the 5 trips NS
 * returns for a route, across two time windows (typically your morning
 * and evening commute). Queries NS directly, using its `dateTime`
 * search anchor — this never touches the deployed Worker at all.
 *
 * This replaces a debug=raw passthrough that used to live in the Worker
 * itself for exactly this kind of one-off investigation. Deploying a
 * diagnostic code path to the live public Worker to answer a question
 * like this is the wrong pattern — this script answers it without
 * deploying anything, ever.
 *
 * Usage:
 *   NS_API_KEY=xxxx npm run sample:ns
 *   NS_API_KEY=xxxx STATION_A=ALMO STATION_B=ASD npm run sample:ns
 *   NS_API_KEY=xxxx WINDOWS="07:00-08:30,17:00-18:30" INTERVAL_MINUTES=15 npm run sample:ns
 *
 * Env vars (all optional except NS_API_KEY):
 *   STATION_A / STATION_B   Route to sample, both directions. Default ASD/UT.
 *   DATE                    yyyy-mm-dd to sample. Default: today.
 *   WINDOWS                 Comma-separated HH:MM-HH:MM ranges (Europe/Amsterdam
 *                           local time). Default "07:00-08:30,17:00-18:30".
 *   INTERVAL_MINUTES        Step between sample points within a window. Default 15.
 *
 * Safety rules for this script (do not weaken these):
 *   - Never log the API key, in any form.
 *   - Never log request headers.
 *   - Only ever logs departure time, track, and transfers count per trip —
 *     the same public schedule fields the app itself displays, never a
 *     full raw response or anything passenger-identifying (there isn't
 *     any in this endpoint, but the restraint is deliberate anyway).
 */

const NS_TRIPS_URL = "https://gateway.apiportal.ns.nl/reisinformatie-api/api/v3/trips";
const AMSTERDAM_TZ = "Europe/Amsterdam";

function parseWindows(spec) {
  return spec.split(",").map((range) => {
    const [start, end] = range.trim().split("-");
    return { start, end };
  });
}

function timesInWindow(start, end, stepMinutes) {
  const times = [];
  let [h, m] = start.split(":").map(Number);
  const [endH, endM] = end.split(":").map(Number);
  const endTotal = endH * 60 + endM;
  while (h * 60 + m <= endTotal) {
    times.push(`${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`);
    m += stepMinutes;
    h += Math.floor(m / 60);
    m %= 60;
  }
  return times;
}

// Netherlands alternates CET/CEST — hardcoding an offset would silently
// mis-anchor every query for half the year. Ask the platform for the
// real offset for this specific date/time instead of computing DST rules.
function amsterdamOffset(dateStr, timeStr) {
  const naiveUtc = new Date(`${dateStr}T${timeStr}:00Z`);
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: AMSTERDAM_TZ,
    timeZoneName: "longOffset",
  }).formatToParts(naiveUtc);
  const tzName = parts.find((p) => p.type === "timeZoneName")?.value ?? "GMT+02:00";
  return tzName.replace("GMT", "").replace(":", "") || "+0200"; // e.g. "+0200"
}

async function fetchTrips(apiKey, fromStation, toStation, dateTime) {
  const url = new URL(NS_TRIPS_URL);
  url.searchParams.set("fromStation", fromStation);
  url.searchParams.set("toStation", toStation);
  url.searchParams.set("previousAdvices", "0");
  url.searchParams.set("dateTime", dateTime);

  const response = await fetch(url.toString(), {
    headers: { "Ocp-Apim-Subscription-Key": apiKey, Accept: "application/json" },
  });
  if (!response.ok) {
    throw new Error(`NS API responded with HTTP ${response.status} ${response.statusText}`);
  }
  return response.json();
}

async function main() {
  const apiKey = process.env.NS_API_KEY;
  if (!apiKey) {
    console.error("NS_API_KEY env var is required (never hardcode it). Example:\n  NS_API_KEY=xxxx npm run sample:ns");
    process.exitCode = 1;
    return;
  }

  const stationA = process.env.STATION_A ?? "ASD";
  const stationB = process.env.STATION_B ?? "UT";
  const date = process.env.DATE ?? new Date().toISOString().slice(0, 10);
  const windows = parseWindows(process.env.WINDOWS ?? "07:00-08:30,17:00-18:30");
  const intervalMinutes = Number(process.env.INTERVAL_MINUTES ?? "15");

  const directions = [
    { label: "ab", from: stationA, to: stationB },
    { label: "ba", from: stationB, to: stationA },
  ];

  console.log(`Sampling ${stationA}<->${stationB} on ${date}, every ${intervalMinutes}min:`);
  for (const w of windows) console.log(`  ${w.start}-${w.end}`);
  console.log("");

  const results = [];
  for (const window of windows) {
    for (const time of timesInWindow(window.start, window.end, intervalMinutes)) {
      const offset = amsterdamOffset(date, time);
      const dateTime = `${date}T${time}:00${offset}`;
      for (const dir of directions) {
        try {
          const body = await fetchTrips(apiKey, dir.from, dir.to, dateTime);
          const trips = (body?.trips ?? []).slice(0, 5);
          const directCount = trips.filter((t) => t?.transfers === 0).length;
          results.push({ window, time, dir: dir.label, trips, directCount });
          const summary = trips
            .map((t) => `${(t?.legs?.[0]?.origin?.plannedDateTime ?? "?").slice(11, 16)}(t${t?.transfers ?? "?"})`)
            .join(" ");
          console.log(
            `${time} ${dir.label}  direct=${directCount}/${trips.length}  ` +
              `${directCount < 2 ? "*** FEWER THAN 2 ***" : ""}  [${summary}]`,
          );
        } catch (err) {
          results.push({ window, time, dir: dir.label, error: err.message });
          console.log(`${time} ${dir.label}  ERROR: ${err.message}`);
        }
      }
    }
  }

  const ok = results.filter((r) => !r.error);
  const fewer = ok.filter((r) => r.directCount < 2);
  console.log("");
  console.log(`Sampled: ${ok.length} (${results.length - ok.length} errors)`);
  console.log(`Fewer than 2 direct trains: ${fewer.length} (${ok.length ? Math.round((100 * fewer.length) / ok.length) : 0}%)`);
}

main();
