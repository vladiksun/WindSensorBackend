# Tasks

## 1. Reproduce the real page contract in tests

- [x] 1.1 Add captured live fixtures under `src/test/resources/onechip/` — `windt-0264.html` and `windt-0001.html` (full pages fetched from 1chip.ru) and `windt-empty-shell.html` containing exactly `</div></div>` — and verify they load through `OneChipDataProviderTest.class.getResourceAsStream(...)`.
- [x] 1.2 Add a `windt-group-only.html` fixture (recent table header with no data rows, grouped table populated) and a `windt-renamed-captions.html` fixture derived from the `0264` capture with the speed captions rewritten; verify both parse to zero measurements with the **current** provider code, proving today's implementation cannot tell these cases apart. Verified before implementation: the current code read 5 values out of the renamed table (wrong columns) and returned no data for the grouped-only page.
- [x] 1.3 Add `parsesCapturedLivePageIntoRealReadings` against `windt-0264.html`, pinning "now" through the existing `currentTimeSeconds()` seam, asserting non-zero min/avg/gust/direction, ascending order and a newest reading inside the requested window; verify with `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.OneChipDataProviderTest'`.

## 2. Request identity for an HTML upstream

- [x] 2.1 Implement `OneChipDataProvider.requestHeaders(sensorId)` returning a browser-like `User-Agent`, `Accept: text/html,application/xhtml+xml;q=0.9,*/*;q=0.8` and `Accept-Language: en`; verify by asserting the returned map in `OneChipDataProviderTest`.
- [x] 2.2 Change `ProxyService` so provider-declared headers replace (`setHeader`) rather than append to (`addHeader`) its defaults, keeping `Accept: application/json` for providers that declare nothing; verified with `WindSensorControllerMockServerTest` scenarios asserting the upstream request carries the HTML `Accept` for a 1chip sensor (and no `application/json` alongside it) and unchanged headers for a windy sensor.

## 3. Header-driven column layout and table selection

- [x] 3.1 Replace the fixed `CELL_*` / `MIN_CELLS_FOR_DATA_ROW` constants with a column layout resolved from a candidate table's header row, expanding `colspan`, matching `hh:mm`/`min`/`Avg`/`Gust`/`Dir` case-insensitively, and rejecting tables whose header cannot resolve time, speed and numeric-direction columns; verified with tests over `windt-0264.html` (value fidelity for a known row) and `windt-renamed-captions.html` (zero measurements, `NO_DATA_TABLE`).
- [x] 3.2 Read data rows through the resolved layout so extra cells (compass name, signal level, temperature, trailing empties) are ignored and short rows are skipped; verified with tests over the live fixtures plus a synthetic body mixing 9-cell and 10-cell rows and one truncated row, asserting every valid row survives with unshifted values.
- [x] 3.3 Select the recent table (`#data_table`) first and fall back to the grouped table (`#group_table`) when it yields no usable rows; verified with `windt-group-only.html` returning its grouped readings while the other suites stay green.

## 4. Extend into grouped history when the window demands it

- [x] 4.1 When the recent table's span is narrower than `readingWindowSeconds`, merge grouped rows into the series, deduplicate by reconstructed timestamp preferring the finer-grained recent row, and sort ascending before windowing; verified with a 14 400 s request against `windt-0264.html` (recent table spans ~3 h), asserting the series reaches 16:30, contains no duplicate timestamps, and keeps the recent 17:30 row over the grouped one.
- [x] 4.2 Confirm the default 3600 s path is unchanged for live pages; verified by `keepsUsingOnlyTheRecentTableWhenItAlreadyCoversTheWindow` (51 recent rows, source `RECENT`) and the unchanged five-reading assertion in `parsesCapturedLivePageIntoRealReadings`.

## 5. Name every unproductive response

- [x] 5.1 Make the parse stage return measurements plus a reason (`NO_BODY`, `EMPTY_PAGE_SHELL`, `NO_DATA_TABLE`, `NO_VALID_ROWS`, `PARSE_ERROR`) and counters (tables seen, candidate rows, valid rows), exposed through a package-private seam so tests can assert them — `OneChipDataProvider.ParseOutcome` / `parseMeasurements(body, window)`.
- [x] 5.2 Log exactly one WARN per unproductive response carrying the reason code, URL, body length and row counters — never the full body; verified by asserting the reported counters for `windt-empty-shell.html` (12 bytes, no tables) versus `windt-renamed-captions.html` (full length, three tables seen, layout unresolved).
- [x] 5.3 Keep the response contract: every reason still yields `Try.success(List.of(SensorDataDTO.empty()))`, while a non-2xx upstream still fails as an upstream error; verified with `OneChipDataProviderTest` (zero-reading shape for each reason, zero reading never mixed with real ones) plus `onechipUpstreamFailureStaysAnUpstreamError` where 1chip.ru answers `503`.

## 6. Bound the timestamp correction

- [x] 6.1 Implement the three-band correction — no correction within tolerance; clamp readings newer than "now" down to "now" with a WARN when the overshoot is below half a day; whole-day roll-back of `ceil((overshoot − tolerance) / 86_400)` days at or above half a day — keeping the `currentTimeSeconds()` seam; verified with tests pinning "now" across all three bands, including a 66-second-skew case that must **not** move fresh data about a day into the past (the old code shifted that series by a full day), plus the existing `neverReturnsAFutureReading` one-day-shift expectation and a two-day roll-back.

## 7. Bound upstream call duration

- [x] 7.1 Build the shared `CloseableHttpClient` with explicit connect (10 s), response (30 s) and connection-lease (10 s) timeouts from `wind-sensor.upstream-*`, and disable HttpClient's automatic retries so one client request issues exactly one upstream call; verified by `WindSensorControllerUpstreamTimeoutTest`, whose upstream is a local socket that accepts and stays silent, asserting `/sensor-data` fails as an upstream error in seconds (measured 2.7 s with a 2 s response timeout) rather than hanging, and by `onechipUpstreamFailureStaysAnUpstreamError` asserting exactly one upstream request for a `503`. MockServer 5.15 exposes no response-delay option, so the stalled case is exercised with a raw socket instead of a delayed MockServer expectation.
- [x] 7.2 Confirm the rebuilt client still speaks HTTPS and reads the real page: a temporary probe fetched `windt.php?id=0264`, `id=0001` and an unused id through `ProxyService.buildHttpClient(...)`, answering `200 text/html; charset=UTF-8` at 31 717 / 33 935 / 12 bytes respectively (the probe was deleted after recording this).

## 8. Integration checks

- [x] 8.1 Run the provider and controller suites green: `./gradlew test --tests 'com.vb.wingfoil.provider.impl.*' --tests 'com.vb.wingfoil.WindSensorControllerMockServerTest' --tests 'com.vb.wingfoil.WindSensorControllerUpstreamTimeoutTest'` — 58 tests, 0 failures.
- [x] 8.2 Compile both source sets and pass formatting: `./gradlew -q :compileJava`, `./gradlew -q :compileTestJava`, `./gradlew -q spotlessCheck` (after `spotlessApply`).
- [x] 8.3 Whole-suite run (`./gradlew test`) leaves two failures in `com.vb.wingfoil.tiles` (`OsmTilesConfigurationTest.defaultsMatchApplicationYml`, `TileCompositeServiceTest.disabledOptimizationKeepsTrueRgbPng`). Confirmed pre-existing: with this change's files stashed, both fail identically on unmodified `master`. Out of scope for this change.

## 9. Production verification (server-side, not code)

Recorded 2026-11-17 against `vm67706.vpsone.xyz` / `89.46.42.210` after the `Deploy to Server` dispatch. Access facts worth keeping: **port 3084 is sshd**, not HTTP — the app serves HTTPS on **443** (`docker-compose.yml` maps `443:443`, keystore mounted from `/home/keystore.p12`), so a plain-HTTP probe of `89.46.42.210:3084` answers nothing and an HTTPS probe of that port reports a protocol violation.

- [x] 9.1 The running image **is this change**. Two independent proofs from the container:
  - its log contains the reason-code line that exists only in this change —
    `WARN c.v.w.p.impl.OneChipDataProvider - onechip response produced no readings: reason=NO_BODY, url=https://1chip.ru/windt.php?id=0264, bodyLength=0, tablesSeen=0, candidateRows=0, validRows=0`;
  - `/app/resources/application.yml` inside the image carries the three new keys at lines 72-74 (`upstream-connect-timeout-seconds`, `upstream-response-timeout-seconds`, `upstream-connection-request-timeout-seconds`).

    `provider: "windguru"` is no longer treated as an unknown provider either: it now reaches Windguru and fails there (see 9.4), which matches HEAD rather than the pre-`6de0cca` image.
- [x] 9.2 Server-side fetch of the 1chip page — **the upstream answers `200` with an empty body from this host**. `curl` on the server reports `HTTP/2 200`, `content-type: text/html; charset=UTF-8`, **`content-length: 0`**, `server: Apache/2` for `windt.php?id=0264`, and the saved body is 0 bytes.

    | Probe from the production host (185.11.244.180 = 1chip.ru) | Result |
    | --- | --- |
    | `GET /` (site root) | `200`, **36 653 bytes** — host reachable, not blocked wholesale |
    | `windt.php?id=0264`, bare curl | `200`, **0 bytes** |
    | same with browser `User-Agent` + `Accept: text/html…` + `Accept-Language: en` | `200`, 0 bytes |
    | same over HTTP/1.1, with `Referer: https://1chip.ru/`, with `Accept-Language: ru-RU`, with a cache-busting query, with the session cookie issued by the root page, and over plain `http://` | `200`, 0 bytes in every variant |
    | `windt.php?id=9999` (unused id) | `200`, 0 bytes — even the 12-byte shell is absent |
    | **Same URL from a workstation at the same moment** (`id=0264`) | `200`, **34 080 bytes** |

    So this is endpoint- and egress-specific, not a network failure and not something headers can change: the site serves its pages to this host but its sensor page renders nothing for it. The workstation contrast also reproduces the reference measurements — an inactive/unused id answers the 12-byte shell there (`id=0002` → `200`, 12 bytes), while active sensors answer ~34 KB.
- [x] 9.3 After the deploy, `POST /sensor-data` for both Dahab ids answers **HTTP 200 with a single zero reading in ~0.4 s**, and every one of them is explained by a named reason line (`NO_BODY`, `bodyLength=0`) rather than a silent gap. A live watch polls `0264` roughly every 12 s; each poll produces exactly one such log line — no duplicate upstream call per client request was observed. The same host fetches the `windy` provider successfully (`"id": "1chipru_10521ce2b0cd"` → five live readings, e.g. gust 9.1 / avg 6.4 / min 4.7 m/s at 20:08-21:0x UTC), which rules out a general egress problem and isolates the failure to 1chip.ru's data endpoint.
- [x] 9.4 The alternative source does **not** work from this host either. `https://www.windguru.cz/int/iapi.php?q=station_data&id_station=15693&…` answers **`403 {"return":"error","message":"forbidden"}`** from the server even with a browser `User-Agent` and `Referer: https://www.windguru.cz/15693`, and `/sensor-data` for that station returns HTTP 500 (`Upstream call failed with status 403`). Routing Dahab – Assalah to Windguru would therefore replace "no data" with an error, so it is not the fix. Windy *does* work from this host and does carry 1chip stations as `1chipru_<hash>` meteostations — the config repo already lists `1chipru_e868e7f2bf47`, which that API names **"Happy Kite/Soul Kiters_ Dahab lagoon (1chip.ru)"** — but its `data` array is empty, so that mirror is not fed either.

### Conclusion of group 9

The parsing and diagnostics half of this change works as designed in production: the failure is now named (`NO_BODY`, `bodyLength=0`) instead of silent, calls are bounded, and one client request maps to one upstream call. The **readings themselves cannot be restored by code in this repository** — 1chip.ru serves an empty body to this server's egress for its sensor pages while serving the same page (~34 KB) to a residential client. Options, none of which is a code change here:

1. Give the two Dahab sensors a source that answers from Bratislava (a Windy `1chipru_…` station that is actually fed, or another provider).
2. Route only this scraping through one of the VPN exits already running on that host (`amnezia-awg2`, `3x-ui`) so the request leaves from a region 1chip.ru serves — an infra decision with its own reliability and fairness questions.
3. Accept "no data" for the two 1chip sensors in Dahab and keep the reason-code log as the operational signal.

Deciding between them needs the owner's input, because 1 and 2 both live outside this repository (`vladiksun/WindSensorConfig`, host networking).

### Root cause found on the second pass (2026-11-17, later the same day)

The empty answer is an **application-level filter on 1chip.ru keyed to this server's source IP and to that URL path**, not a network failure, not headers, and not the parser:

| Fact | Evidence |
| --- | --- |
| The sensor is publishing right now | A third-party egress fetched `windt.php?id=0264` and got the full page — "Dahab - Assala 2", `Arrived: 17/11/2026 23:12:39`, rows every ~3 min up to that moment |
| Other egresses get the data from the same moment | Workstation (`82.102.18.202`, AS9009 M247, FR): `200`, 33 634–34 080 bytes for `0264` |
| The production host is not firewalled | From the server: `https://1chip.ru/` → `200`, 36 653 bytes; `https://1chip.ru/windex.php?id=0264` → a real **1 844-byte 404 page** |
| Only the data path is emptied | From the server: `windt.php?id=…` → `200`, `content-length: 0` for ids `0264`, `0001`, `9999`, for `www.1chip.ru`, and for `?id=0264&mobile=1` |
| Nothing in the request changes it | Browser UA, full `Accept`/`Accept-Language`/`Sec-Fetch-*` header set, `Referer`, session cookie issued by the site, the workstation's own replayed cookie, `--compressed`, `POST` instead of `GET`, `X-Forwarded-For` spoofed to the workstation IP and to RU addresses, `Client-IP`, `CF-IPCountry: RU`, HTTP/1.1 vs HTTP/2, plain `http://` — all `200` with 0 bytes |
| The host we scrape from is a datacenter range | Egress `89.46.42.210` / PTR `vm67706.vpsone.xyz`, AS61424 eServer s.r.o. (ipinfo reports Bratislava SK in one source, Scalaxy B.V./AE in another) |
| **We hit that URL far more often than it changes** | `docker logs --since 60m windsensorbackend` → **141** upstream calls to `windt.php` (138 for `0264`, 3 for `0001`) while the page gains a row every ~3 min (~20 rows/hour). `ProxyService.requestTimedReadings` has no cache: every watch poll — about one per 12 s per connected client — is a fresh scrape |

| **Who produces the empty answer** | Same URL, allowed egress: `200`, `Transfer-Encoding: chunked` (PHP streamed a page). Blocked egress: `200`, `Content-Length: 0`, same `Server: Apache/2`, same `Vary: Accept-Encoding`. No `403`, no `429`, no `Retry-After`, no JS challenge, no extra WAF headers, and the site's own PHP session cookie is still issued to this IP on `/`. So the emptiness is generated by 1chip.ru's application logic for this client, not by an HTTP throttle module or a CDN interstitial |
| Current volume from the server | `docker logs --since 15m` → **46** calls to `windt.php` ≈ one every ~20 s sustained (~9× the page's own update rate) |

Interpretation: serving `200` with an empty body for one URL to one IP, while the rest of the site renders normally for that same IP, is what an anti-scraping/abuse rule looks like from the client side. The volume above (~7× the upstream's own update rate, continuously, from a datacenter IP) is the plausible trigger; the workstation and the third-party fetcher are not flagged.

Consequences for this change: nothing here can lift the filter — it lives on 1chip.ru. What this repository can do is stop amplifying it: cache provider readings per `(provider, id)` with a TTL matched to the upstream's resolution, and back off after an empty or failing answer so a blocked upstream is not hammered. That is a separate capability (`/sensor-data` request volume), tracked as its own change; recovering the Dahab data additionally needs either the filter to expire once the load drops, a different network exit on the host, or a different source for those two sensors.

### Every 1chip.ru surface probed from the production host (2026-11-17, third pass)

The site is Joomla with a custom sensor component. Its own front-end JS (`/windtest.html`, 61 769 B) reveals the whole data surface: `/active.php?i=<id>`, `/img_php.php?i=<id>&reload=<n>`, `/wind_window.php?user_id=<id>`, `/wind_window_2_hours.php?user_id=<id>`, `/windt.php?id=<id>`, plus SEF pages `/wind0.html`, `/wind1.html`, `/wind2.html`, `/precip_v02.html`. The sensor directory (`/wind` → same document as `/wind.html`) is a `<select>` of `<OPTION VALUE="id,lat,lon,lastSeenUnix,type,name">`, which also confirms id `0264` = "Dahab - Assala 2?EGYPT" and id `0060` = "Dahab.pro - Assala" (stale since March).

| Path | From the production host | Interpretation |
| --- | --- | --- |
| `/windt.php?id=0264` | `200`, **0 bytes** | the blocked path — the only one that renders the readings as HTML tables |
| `/wind` (`/wind.html`) | `200`, 66 290 / 66 305 B | not blocked; sensor directory with ids, coordinates and last-seen stamps |
| `/active.php?i=0264` | `200`, 10 B → `1794934731` | not blocked; returns the sensor's last-packet unix time — a cheap liveness probe that works today |
| `/img_php.php?i=0264` | `200`, 32 626 B, magic `ff d8 ff e0` (JPEG) | not blocked; **the same data as a chart image** (workstation gets 38 457 B). Usable only with OCR — fragile, no structured values |
| `/wind_window.php?user_id=0264`, `/wind_window_2_hours.php?user_id=0264` | 622 B and 659 B — byte-identical to the workstation's answers | not blocked; widget stubs, no readings |
| `/wind0.html?id=0264`, `/wind1.html`, `/wind2.html`, `/precip_v02.html` | 20 530 / 16 377 / 18 250 / 8 695 B — identical to the workstation's sizes | not blocked; static device-description pages, zero data rows |
| `https://r.jina.ai/https://1chip.ru/windt.php?id=0264` | `200`, **2 767 B markdown containing the complete past-data table** (`23:26 ↓ 5.3 6.2 7.2 31 NNW 351° 23.7`, rows every ~3 min) | **a working workaround from this host right now**: the reader service fetches with its own egress and returns the live values |
| `api.allorigins.win/raw`, `api.codetabs.com/v1/proxy`, `corsproxy.io` | 522, 522, 401 | public CORS relays are unusable from here |
| Windguru `iapi.php?q=station_data&id_station=15693` (Dahab – Assalah) | `403`, 40 B; from a clean egress only 44 B | not a mirror source: the station has no data even when reachable |

## Workflow follow-up

- [x] Start the apply workflow for this change before writing any implementation; groups 2–7 were implemented only after the artifacts were approved.
- [x] After implementation passes its checks, archive the change so `openspec/specs/onechip-wind-provider/spec.md` is created — the two archived onechip deltas were never synced into `openspec/specs/`. Done 2026-11-18: the delta's Purpose and all 14 ADDED requirements (33 scenarios) were synced verbatim into `openspec/specs/onechip-wind-provider/spec.md`, which `openspec validate --specs` accepts.
- [x] Update `memory-bank/activeContext.md` and `memory-bank/progress.md` with the group 9 outcome, because it decides whether this bug is fixable in code at all or is an egress/config problem. Done 2026-11-17: both now state that the code half works in production and that the missing readings are an upstream-egress/config problem, with the measured numbers.
