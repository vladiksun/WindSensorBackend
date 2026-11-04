# Design

## Context

See proposal.md for motivation. Current state: `WindDataProvider<T>` is the plugin contract; `BaseWindyDataProvider<T>` supplies `name`/`url`/`objectMapper` plus the generic windowing helpers (`buildTimedReadings`, `getReadingsByInterval`, `reduceWindowReadings`). Providers are `@Singleton` beans collected into a name-keyed map in `ProxyService`, configured by `WindDataProviderConfig` (`@EachProperty("wind-providers")`, fields `name`, `url`, `timezone`). `ProxyService` builds the outbound `HttpGet` itself with Apache HttpClient5 and adds only `Accept: application/json`; non-2xx upstream responses become `IOException("Upstream call failed with status <n> …")` and `GlobalExceptionHandler` maps them to 500.

### Live network analysis of `https://www.windguru.cz/station/15693` (Dahab – Assalah)

Station page traffic (captured with DevTools) shows the page is a shell that loads its data from `GET /int/iapi.php?q=<action>` JSON endpoints. Relevant calls:

| Call | Purpose | Notes |
| --- | --- | --- |
| `?q=station_data&id_station=15693&from=<ISO-UTC>&to=<ISO-UTC>&avg_minutes=4&graph_info=1` | observed reading history for the graph | **the endpoint this provider needs** |
| `?q=station_data_current&id_station=15693&avg_min=0&date_format=…` | latest single reading | same field names, no history |
| `windguru.net/int/iapi.php?q=station&id_station=15693&weather=false` | station metadata (`spotname`, `lat/lon`, `timezone: Africa/Cairo`) | not needed for readings |
| `?q=token_refresh` → `X-WG-Token` header on later calls | browser session token | **not required**: responses carry `x-wg-token-state: missing` and still return 200 |

Verified behavior of `station_data` (measured against the live endpoint):

- Response is **columnar**: `unixtime[]`, `datetime[]`, `wind_avg[]`, `wind_max[]`, `wind_min[]`, `wind_direction[]`, `temperature[]`, `mslp[]`, `rh[]`, `gustiness[]`, plus scalars `sunrise`, `sunset`, `startstamp`, `endstamp`, `tzoffset`. All arrays are equal length and **ascending**.
- Speeds are **knots**: the page renders `23.3 knots` while `station_data_current` returns `"wind_avg": 23.29` for the same instant — no client-side conversion, so values are used verbatim (`SensorDataDTO` is knot-based like Windy/Neduet).
- Directions are numeric degrees; `unixtime` is epoch **seconds** (the unit `getReadingsByInterval` expects) — so no time-zone math is needed at all, unlike the 1chip provider.
- **A `Referer` header is mandatory.** Without it every data call returns **401**, whatever the User-Agent (`Apache-HttpClient/5.5`, `curl/8`, `Mozilla/5.0`, empty UA — all 401). With a `Referer` present, the Java HttpClient UA and `Accept: application/json` both succeed with no cookies, no session and no `X-WG-Token`.
- `from`/`to` must be ISO-8601 UTC (`2026-11-04T06:00:00Z`, milliseconds optional). Epoch numbers or unparseable text → **400**; missing `from`/`to` → **500**; unknown station id → **400**.
- A valid range with no data returns **200 with body `[]`** (a JSON array, not the usual object).
- Gaps are published as JSON `null` **inside** the numeric arrays (10-day probe: 445 of 3356 points had a null `wind_avg`).
- Cost/limits observed: raw resolution ≈ 4 min; 24 h @ `avg_minutes=1` → 362 points / ~25 KB; 7 days @ 4 min → 2373 points; 10 days @ 4 min → 3356 points / ~226 KB. `avg_minutes` accepts any positive integer (1, 4, 5, 7, 10, 60 tested).

## Goals / Non-Goals

**Goals:**
- Serve station 15693 (and any Windguru station) through the existing `/sensor-data` contract with zero client-visible change.
- Reuse `BaseWindyDataProvider` windowing verbatim; add only URL building and payload unzipping.
- Make the missing-`Referer` problem solvable in a way that keeps provider specifics out of shared code.
- Fail soft on Windguru's no-data and gap payloads instead of erroring every request.

**Non-Goals:**
- No caching, rate limiting or retry policy (matches the current stateless design; listed as follow-up in memory-bank).
- No station metadata (`/spots-data` content stays sourced from the external spots config).
- No use of the AI/"wg3g" prediction models or forecast endpoints — only observed sensor readings.
- No `X-WG-Token` session emulation unless Windguru starts enforcing it (see Risks).

## Decisions

1. **One upstream call: `q=station_data`.** The windowed payload also covers the "latest reading only" case, because `buildTimedReadings` returns `getLastReading(data)` when `readingWindowSeconds == 0 && numberOfReadings == 0`.
   - Alternative considered: call `q=station_data_current` for latest-only requests — rejected: two code paths and two URL shapes for no observable gain, and the current reading carries no history for any other window.

2. **The time range is computed inside `getCallUrl` as `[now − LOOK_BACK, now]`.** The provider contract hands a provider only the `sensorId` (`getCallUrl(String sensorId)`); the resolved `readingWindow`/`numberOfReadings` are known only later, in `ProxyService.handleResponse`. A generous fixed look-back (24 h) therefore has to cover any window a client may request; the shared interval reader then anchors on the newest published point and trims to the requested window.
   - Alternative considered: pass the resolved window into `getCallUrl` / `ResponseHandlerContext` — rejected: changes the provider contract and the `ProxyService` flow for a problem that a fixed range already solves, since today's windows are 3600 s by default and 5400 s for the Dahab config.
   - Consequence to record: a request whose window exceeds 24 h yields at most the readings available in 24 h (never an error).

3. **`Referer` is supplied through a new provider hook, not hard-coded in shared code.** `WindDataProvider` gains `default Map<String, String> requestHeaders(String sensorId)` returning an empty map; `ProxyService` applies the entries to the `HttpGet` after the existing `Accept` header. Windguru returns `Map.of("Referer", "https://www.windguru.cz/station/" + sensorId)`.
   - Alternative considered: a static `headers` map on `WindDataProviderConfig` — rejected: it cannot express a per-station referer and would add config surface that Windy/Neduet/1chip never use.
   - Alternative considered: hard-code the referer in `ProxyService` — rejected: leaks one provider's requirement into shared plumbing (it would then be sent to Windy and Neduet too).
   - Alternative considered: let each provider build its own HTTP request — rejected: duplicates connection handling, status checks and error messages that `ProxyService` already owns.

4. **Static query parameters live in the configured URL template; only `from`/`to` are appended by code.** `application.yml` gets `windguru.url: https://www.windguru.cz/int/iapi.php?q=station_data&id_station=%s&avg_minutes=1&graph_info=1`, i.e. exactly one `%s` (because `getCallUrl` does `getUrl().formatted(sensorId)`), and the provider appends `&from=<iso>&to=<iso>`.
   - Rationale: `avg_minutes` stays tunable per environment without a code change, and no new `WindDataProviderConfig` field is needed. `avg_minutes=1` selects Windguru's finest published resolution (~4 min), which keeps gust fidelity for short windows; payloads stay ~25 KB for 24 h.
   - Alternative considered: new `history-hours` / `avg-minutes` config fields — deferred to Open Questions rather than expanding the shared config bean now.

5. **Two models in `com.vb.wingfoil.response.windguru`.** `WindguruStationData` is a `@Serdeable` record whose arrays are boxed and nullable (`List<Float>`, `List<Long>`) for `unixtime`, `wind_avg`, `wind_max`, `wind_min`, `wind_direction`; unknown members (`temperature`, `mslp`, `rh`, `gustiness`, `sunrise`, …) are ignored. `WindguruMeasurement(float min, float avg, gust, dir, long timestamp)` is a plain record like `OneChipMeasurement` because it is built by unzipping the columns, not deserialized.
   - Rationale: Windguru publishes `null` **inside** numeric arrays, so primitive `float[]`/`long[]` would either fail or silently coerce gaps to 0 and invent readings.

6. **Shape guard before deserialization.** A blank body, or a body whose first non-whitespace character is `[`, yields `List.of(SensorDataDTO.empty())` at debug level — that is exactly the payload Windguru returns for an empty range, and letting it hit the record deserializer would log an error on every quiet period. Any other deserialize failure recovers to a single empty reading and logs, mirroring `WindyDataProvider.recover`.

7. **Row unzipping skips incomplete rows.** A published index is emitted only when `unixtime` and all four wind values are non-null; otherwise it is skipped (same leniency as 1chip's malformed-row skip). Order stays ascending, so `getReadingsByInterval`/`getLastReading` work unchanged. Field mapping matches the other providers: `wind_max → windMax`, `wind_avg → windAvg`, `wind_min → windMin`, `wind_direction → windDirection`, `unixtime → timestamp`.

8. **No new dependencies.** The payload is JSON; `micronaut-serde-jackson` and HttpClient5 are already in the build, so nothing is added to `build.gradle` (unlike the 1chip change, which needed Jsoup).

## Risks / Trade-offs

- [`Referer` is an anti-bot heuristic, not a documented contract — Windguru could later demand the `X-WG-Token` header or session cookies] → The hook added here is generic: adding `User-Agent`/`X-WG-Token` later is one line inside `WindguruDataProvider.requestHeaders`, no further `ProxyService` change. A 401 already surfaces in the existing log line with status + body snippet, so drift is observable; fallback is to replay the `q=token_refresh` handshake inside the provider.
- [`iapi.php` is undocumented and can change shape (renamed fields, different `q=` action)] → Keep the endpoint pinned in config so it can be retargeted per environment; keep unknown members ignored; keep the payload-shape guard + `recover` so a shape change degrades to an empty reading instead of a 500 storm. The unit test fixture is a captured live payload, so re-verifying against the live endpoint shows drift explicitly.
- [Fixed 24 h look-back silently truncates requests for windows longer than 24 h] → Documented in the spec as "at most the readings available"; raise the constant or make it configurable if such requests appear (Open Question).
- [Payload size grows with look-back × `avg_minutes=1`] → Bounded by choosing 24 h (~362 points, ~25 KB measured). A 7-day raw window measures ~2 400 points and would be several hundred KB per request.
- [Server-to-server polling could be rate-limited or blocked] → Exactly one upstream call per client request (no secondary metadata call, no polling), matching current providers; consider caching/rate limiting as a separate change if the provider is enabled for many spots (memory-bank already lists caching/rate limiting as a future enhancement).
- [Stations with recent gaps return fewer readings than `numberOfReadings`] → Accepted: identical to 1chip behaviour, and the interval reader only ever returns readings that exist.
- [`[]` treated as "no data" rather than an error] → Consistent with every existing provider's blank/unparseable handling (`SensorDataDTO.empty()`), so clients keep one uniform contract.

## Migration Plan

1. Merge provider + config, deploy the backend (the new key is additive; nothing existing reads it).
2. Update the external `wingfoil-spots.json` entry for Dahab – Assalah to `"provider": "windguru"`, `"id": "15693"` (that file lives outside this repo — coordinate with its owner); roll one spot first and compare `/sensor-data` output against the station page before switching other spots.
3. Rollback = remove/ignore the `windguru` provider entry in `application.yml` (requests for that provider then fail with "Provider not found: windguru", which is loud rather than silently wrong) and point the spot config back to its previous provider. No data migration, no API versioning, no persisted state.

## Open Questions

- Should the 24 h look-back become a `history-hours` config key on `WindDataProviderConfig`, or stay a constant until someone actually asks for a longer window?
- Is `graph_info=1` worth keeping? It only adds sunrise/sunset metadata our parser ignores; dropping it slims the payload slightly.
- Do we want `/spots-data` to expose Windguru station metadata (name, lat/lon, timezone from `q=station`) later, or keep spot metadata exclusively in the external spots config?
- Should a 401/403 from Windguru be mapped to a distinct client-facing error (e.g. 502) instead of the current generic upstream-failure 500? Out of scope for this change; affects all providers equally.
- Confirm automated server-side use of `iapi.php` is acceptable at our request volume (ToS/robots check) before enabling the provider for many spots.


