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
- pagination beyond the first 5 trips — that's NS's own per-call cap on
  this endpoint (`previousAdvices`/`nextAdvices` are decommissioned, not
  usable to raise it); getting more would cost a second NS API call per
  request via the response's scroll context, which we've deliberately
  not built

If you want any of the above, this repo is a fine starting point to fork
and extend, but that's a different project from what's here.

## Repo layout

```
worker/   Cloudflare Worker (TypeScript) — proxies the NS API
wear/     Wear OS app (Kotlin)           — tile + companion app
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

Optionally, also set `STATION_A_SHORT` / `STATION_B_SHORT` — short display
names used only by the watch tile's header (the phone-sized main screen
always shows the full names). Mine are `"Oostvaarders"` and `"Amsterdam"`.
The Worker passes whatever you set through verbatim, with no truncation
logic of its own — pick something that fits your watch's screen. If
either is left unset, that side just falls back to the full station name,
so this works out of the box on a fresh clone with zero configuration.

Set your API key as a secret (never in a file that gets committed):

```bash
npx wrangler login   # first time only
npx wrangler secret put NS_API_KEY
# paste your Ocp-Apim-Subscription-Key value when prompted
```

**Also set `VERTREK_KEY` — this is not optional.** The Worker's `/next`
endpoint requires a matching `X-Vertrek-Key` header on every request; if
`VERTREK_KEY` isn't set, the Worker refuses *every* request with `500`
(it fails closed, not open — it will not silently serve trips to anyone
who finds your `workers.dev` URL). Make up your own secret value — it
doesn't come from NS, it's just a shared secret between your Worker and
your watch app:

```bash
npx wrangler secret put VERTREK_KEY
# paste a random value you generate yourself, e.g.:
openssl rand -hex 32
```

You'll enter this same value into the watch app's `local.properties` as
`VERTREK_API_KEY` (see Part 2 setup) so it can send it back as
`X-Vertrek-Key`. If you skip this step, expect every request to
`/next` to come back `500 SERVER_MISCONFIGURED` — that's the Worker
telling you the secret isn't set, not a bug.

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
VERTREK_KEY=your-local-dev-vertrek-key
```

Run it locally with `npm run dev`, then:

```bash
curl -H "X-Vertrek-Key: your-local-dev-vertrek-key" "http://localhost:8787/next?dir=ab"
```

### 3. Build and sideload the watch app

The watch app is a single Wear OS module (`wear/`) — a tile
(`androidx.wear.protolayout`) plus a Compose companion screen. It talks
only to your own Worker from step 2, using the `X-Vertrek-Key` header.

Create `wear/local.properties` (gitignored, never committed):

```properties
sdk.dir=/path/to/your/Android/sdk
NS_WORKER_BASE_URL=https://your-worker-name.your-subdomain.workers.dev
VERTREK_API_KEY=the-same-value-you-set-as-the-Worker-secret-VERTREK_KEY
```

Both are read into `BuildConfig` at build time — never hardcoded in
source. They're where the app sends requests and the key it
authenticates with. Must match your deployed Worker's URL and its
`VERTREK_KEY` secret exactly, or every request comes back `401`.

Optionally, also tune the HTTP client's timeouts (milliseconds) — these
default to 10s/20s/20s if unset, sized for a phone Bluetooth/hotspot
companion link rather than just Wi-Fi:

```properties
CONNECT_TIMEOUT_MILLIS=10000
SOCKET_TIMEOUT_MILLIS=20000
REQUEST_TIMEOUT_MILLIS=20000
```

Station display names (e.g. "Almere Oostvaarders → Amsterdam
Centraal") aren't build config — they come from the Worker's `/next`
response, which resolves them server-side from your `STATION_A`/
`STATION_B` codes (see step 2). The app never configures or sends
station codes itself.

Build a debug APK:

```bash
cd wear
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

#### Signed release build (optional, for a longer-lived sideload)

Generate a local keystore once (never commit it — already gitignored):

```bash
cd wear
keytool -genkeypair -v -keystore vertrek-release.keystore -alias vertrek \
  -keyalg RSA -keysize 2048 -validity 10000
```

Create `wear/keystore.properties` (gitignored) pointing at it:

```properties
storeFile=vertrek-release.keystore
storePassword=your-store-password
keyAlias=vertrek
keyPassword=your-key-password
```

```bash
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties`, `assembleRelease` still succeeds but
produces an unsigned APK — fine for `./gradlew test`/CI, not installable
on a device.

#### Install on a Galaxy Watch over wireless debugging

On the watch: **Settings → About watch → tap "Software version" 7
times** to unlock Developer options, then **Settings → Developer
options** → enable **ADB debugging** and **Wireless debugging**. Open
**Wireless debugging → Pair new device** — it shows a 6-digit code and a
`pairing IP:port`, and separately the screen shows a `connection IP:port`
for after pairing.

```bash
# 1. Pair once (IP:port and code from "Pair new device" on the watch)
adb pair <PAIRING_IP>:<PAIRING_PORT>
# enter the 6-digit code when prompted

# 2. Connect (IP:port shown on the main Wireless debugging screen —
#    usually a different port than the pairing one)
adb connect <WATCH_IP>:<CONNECT_PORT>

# 3. Confirm it's there
adb devices

# 4. Install (-r replaces an existing install, keeping app data)
adb install -r app/build/outputs/apk/debug/app-debug.apk
# or, for a signed release build:
adb install -r app/build/outputs/apk/release/app-release.apk
```

Pairing is one-time per watch/computer pair; after that, `adb connect`
alone is enough (the watch's IP can change between networks, so re-check
the Wireless debugging screen if `connect` fails).

#### Or: run it on a Wear OS emulator instead of a physical watch

No physical watch needed for day-to-day iteration. One-time setup:

```bash
# From $ANDROID_HOME/cmdline-tools/latest/bin (or wherever yours lives).
# Wear OS 5 / API 34, arm64-v8a, no Google Play — matches the physical
# Galaxy Watch closely enough for this app, and is the architecture your
# Mac's own CPU can run without emulation-of-emulation overhead on Apple
# Silicon.
sdkmanager "emulator" "system-images;android-34;android-wear;arm64-v8a"

# "Wear OS Large Round" is the 454x454 round profile — the actual size
# used by most current round watches. Answer "no" to the "custom
# hardware profile" prompt.
avdmanager create avd -n vertrek-wear \
  -k "system-images;android-34;android-wear;arm64-v8a" \
  -d wearos_large_round

# A second AVD on the small-round (384x384) profile — the tightest
# common Wear screen. Useful for checking that the header's long
# station names actually ellipsize instead of clipping/wrapping when
# there's meaningfully less width to work with.
avdmanager create avd -n vertrek-wear-small \
  -k "system-images;android-34;android-wear;arm64-v8a" \
  -d wearos_small_round
```

Start whichever one you want to iterate on:

```bash
$ANDROID_HOME/emulator/emulator -avd vertrek-wear -no-window -no-audio -no-boot-anim
$ANDROID_HOME/emulator/emulator -avd vertrek-wear-small -no-window -no-audio -no-boot-anim
# drop -no-window if you want to see the round watch face on screen
```

Wait for it to finish booting (`adb devices` shows `device`, not
`offline`), then the same one-liner you'd use for any change:

```bash
cd wear && ./gradlew installDebug
```

`installDebug` installs on every connected device/emulator `adb` sees —
running both AVDs at once installs on both in one command, useful for
exactly this kind of side-by-side screen-size comparison. Target just
one with `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`
instead if you only want it on one.

As on a real watch, **the tile is not added automatically** —
`installDebug` only installs the app. Add it the same way you would on
your own wrist: long-press the watch face → **Edit** → pick a tile slot
→ find "Vertrek" in the tile list.

The emulator reaches the public internet like any other Android
device/VM — no extra network setup is needed to hit your deployed
Worker; `NS_WORKER_BASE_URL`/`VERTREK_API_KEY` from `local.properties`
work unchanged.

---

## Worker: API reference

```
GET /next?dir=ab|ba
X-Vertrek-Key: <your VERTREK_KEY secret>
```

Every request must include a valid `X-Vertrek-Key` header — see Setup
step 2. Missing, empty, or wrong key: `401`. `VERTREK_KEY` not configured
on the Worker at all: `500` (fails closed, never open). Neither case
calls the NS API.

`dir=ab` is `STATION_A` → `STATION_B`, `dir=ba` is the reverse. Returns up
to `MAX_TRIPS` upcoming trips (a `wrangler.jsonc` var, default and NS's
own hard cap 5 — see Non-goals), cached
for 30 seconds per `dir`:

```json
{
  "dir": "ab",
  "fromStationName": "Almere Oostvaarders",
  "toStationName": "Amsterdam Centraal",
  "fromStationShort": "Oostvaarders",
  "toStationShort": "Amsterdam",
  "trips": [
    {
      "departureTime": "2026-11-02T12:08:00+0100",
      "arrivalTime": "2026-11-02T12:41:00+0100",
      "delayMinutes": 5,
      "track": "4b",
      "cancelled": false,
      "crowdForecast": "MEDIUM"
    }
  ]
}
```

`fromStationName`/`toStationName` are the full display names, resolved
server-side from the trip response — the watch app renders these
directly and never sees `STATION_A`/`STATION_B`. `fromStationShort`/
`toStationShort` mirror `STATION_A_SHORT`/`STATION_B_SHORT` verbatim (see
Setup step 2), falling back to the full name when the corresponding var
is unset; only the tile header uses these, the main screen always uses
the full names. `crowdForecast` is `LOW` | `MEDIUM` | `HIGH` | `UNKNOWN`:
the NS API reports it per leg, so multi-leg trips are reduced to their
busiest leg.

All error responses are machine-readable JSON:

```json
{ "error": { "code": "UNAUTHORIZED", "message": "..." } }         // 401
{ "error": { "code": "SERVER_MISCONFIGURED", "message": "..." } } // 500
{ "error": { "code": "NS_API_UNAVAILABLE", "message": "..." } }   // 502
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
