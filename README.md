# vertrek

A Cloudflare Worker + Wear OS tile that shows the next few departures for
**one fixed commute route** on the Dutch railway (NS) network, straight on
your watch face.

![Mockup of the watch tile](docs/tile-placeholder.svg)
*(placeholder mockup — not a real screenshot yet)*

You deploy your own Worker, with your own NS API key and your own two
stations. There is no shared instance, no server run by the author, and
no account system — this is a template you run for yourself.

## Non-goals

This project is deliberately small in scope. It does **not** have, and
never will (pull requests adding these will be declined):

- a station picker — the two stations are set once, in your Worker config
- GPS / location awareness
- bus, tram, or metro departures — NS trains only
- an iOS app
- support for more than one commute route per deployment

If you want any of the above, this repo is a fine starting point to fork
and extend, but that's a different project from what's here.

## Repo layout

```
worker/   Cloudflare Worker (TypeScript) — proxies the NS API
wear/     Wear OS app (Kotlin)           — tile + companion app, not built yet
```

## Setup

### 1. Get your own NS API key

1. Sign up at [apiportal.ns.nl](https://apiportal.ns.nl).
2. Subscribe to the **NsApp** product.
3. Copy your `Ocp-Apim-Subscription-Key` — you'll use it as `NS_API_KEY`
   below. NS API usage is subject to their terms (see the notice at the
   bottom of this README) — this key is yours, tied to your account, and
   should never be committed or shared.

### 2. Deploy the Worker with your key and stations

```bash
cd worker
npm install
```

`worker/wrangler.jsonc` is checked into git with placeholder example
stations (Amsterdam Centraal / Utrecht Centraal) — it's the public
template, not your personal config. Keep your real stations out of it so
`git pull` never conflicts with your own edits:

```bash
cp wrangler.jsonc wrangler.local.jsonc   # gitignored — verify with:
git check-ignore -v wrangler.local.jsonc
```

Edit `STATION_A` / `STATION_B` in `wrangler.local.jsonc` to your own two
NS station codes (look them up at
[ns.nl/stationsinformatie](https://www.ns.nl/stationsinformatie)), then
always pass `-c wrangler.local.jsonc` so Wrangler uses it instead of the
tracked file:

```bash
npx wrangler dev -c wrangler.local.jsonc      # local dev, your stations
npx wrangler deploy -c wrangler.local.jsonc   # deploy, your stations
```

(`.dev.vars` isn't used here — Wrangler only reads it for `wrangler dev`,
not for `wrangler deploy`, so it can't hold `STATION_A`/`STATION_B` for a
real deployment. A full `-c` config file is the mechanism that works for
both.)

If you'd rather not keep a second config file at all, skip
`wrangler.local.jsonc` and just edit `wrangler.jsonc` directly — nothing
about your two station codes needs to be secret, this is purely to keep
`git pull` clean if you fork this repo and want to track upstream changes.

Set your API key as a secret (never in a file that gets committed):

```bash
npx wrangler login   # first time only
npx wrangler secret put NS_API_KEY
# paste your Ocp-Apim-Subscription-Key value when prompted
```

Deploy:

```bash
npm run deploy                              # uses wrangler.jsonc (examples), or
npx wrangler deploy -c wrangler.local.jsonc # uses your real stations
```

This gives you `https://<worker-name>.<your-subdomain>.workers.dev` —
your own private endpoint. Note it down; the watch app needs it.

For local development, create `worker/.dev.vars` (gitignored, never
committed):

```
NS_API_KEY=your-local-dev-key
```

Run it locally with `npm run dev`, then `curl "http://localhost:8787/next?dir=ab"`.

### 3. Build and sideload the watch app

Not built yet — this section will cover building a signed release APK and
installing it on a Galaxy Watch over wireless `adb` once the `wear/`
module exists.

---

## Worker: API reference

```
GET /next?dir=ab|ba
```

`dir=ab` is `STATION_A` → `STATION_B`, `dir=ba` is the reverse. Returns up
to 3 upcoming trips, cached for 30 seconds per `dir`:

```json
{
  "dir": "ab",
  "trips": [
    {
      "departureTime": "2026-11-02T12:08:00+0100",
      "delayMinutes": 5,
      "track": "4b",
      "durationMinutes": 33,
      "transfers": 0,
      "cancelled": false
    }
  ]
}
```

On an NS API failure, it responds `502` with a machine-readable body:

```json
{ "error": { "code": "NS_API_UNAVAILABLE", "message": "..." } }
```

## Development

```bash
cd worker
npm test          # vitest, against a recorded fixture — no network calls
npm run typecheck
```

CI (`.github/workflows/ci.yml`) runs `npm install`, `npm run typecheck`,
and `npm test` on every push/PR. It does **not** run `verify:live` — that
needs a real `NS_API_KEY` and is meant to be run manually.

### A note on the NS OpenAPI spec

`apiportal.ns.nl` renders its API docs client-side and requires a
signed-in session to reach the underlying management API (confirmed by
reading the portal's `config.json` and calling its management API
directly — anonymous requests get a `401`). Without portal credentials,
the exact `/v3/trips` request/response shape used in
`worker/src/ns-types.ts` was cross-checked against two independent
real-world clients built against the live v3 API, which agree exactly on
field names — but it hasn't been confirmed against NS's own spec.

If you have your own NS API key, you can check the assumed schema against
the live API yourself:

```bash
NS_API_KEY=xxxx FROM_STATION=ASD TO_STATION=UT npm run verify:live
```

This script only ever prints field **names** it found missing or new —
never your API key, never request headers, never the actual response
body (which is live train data for whatever route you query).

## License

[MIT](LICENSE) © Stepan Suvorov.

MIT covers this code only; use of the NS API is governed by NS terms and
requires your own key.

---

Not affiliated with or endorsed by Nederlandse Spoorwegen. NS API usage is
subject to their terms; the API is intended for private, non-commercial
use.
