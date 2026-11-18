# Design

## Context

See `proposal.md` — Why for motivation and the measurements that narrow the problem to "the upstream served the backend a page with nothing parsable, and the provider cannot say so".

Constraints and current-state facts this design has to work with:

* The call path is `WindSensorController` → `ProxyService.requestTimedReadings(...)` → `provider.getCallUrl(id)` + `provider.requestHeaders(id)` → one Apache HttpClient5 `HttpGet` → `provider.extractTimedReadings(id, body, window, count)`. Window/count reduction lives in `BaseWindyDataProvider` and is measured **from the newest parsed reading**, not from `now`, so stale-but-parsable data still produces readings today.
* Every unproductive 1chip response currently ends as `Try.success(List.of(SensorDataDTO.empty()))` — one zero reading, no log line distinguishing causes.
* The live page (captured for ids `0264` and `0001`) is not what the unit-test fixture models: it declares `charset=UTF-8`, its "Arrived" line sits inside a table row, the recent history lives in `div#data_table` (~50 rows of ~3-minute samples covering roughly 3 h), older history lives in `div#group_table` (15-minute groups), the header row has **7** `<td>` cells with `Gust` and `Dir` each spanning two columns, and data rows carry **9 or 10** `<td>` cells depending on position. Parsing therefore works today only because index 7 happens to be the direction cell.
* 1chip.ru answers `200` with a 12-byte `</div></div>` body when it has nothing to render, and is unreachable (`522`) from at least two foreign fetch proxies; the deployed backend runs in Bratislava while the pages render fully for residential clients. The shared HttpClient is created with `HttpClients.createDefault()`, i.e. **no response timeout**.
* The running production image predates `6de0cca` (windguru), so any behavior shipped by this change needs a manual `Deploy to Server` dispatch.

## Goals / Non-Goals

**Goals:**

* Get real readings back for 1chip sensors where the upstream is willing to serve them, and make it obvious in the logs when it is not.
* Remove position-luck from parsing so a column shift on the upstream page produces a named failure instead of wrong numbers.
* Keep the public `/sensor-data` contract byte-compatible: same request shape, same response shape, including the single zero reading when nothing is available.

**Non-Goals (design level, in addition to the proposal's):**

* No caching layer, no request coalescing, no retry policy — each client request still maps to one upstream call.
* No per-sensor time zone or per-sensor scraping options; the provider-level `timezone` setting stays as is.
* No client-visible error/status channel for "provider has no data" (that needs a coordinated watch change).
* No change to how windy, neduet and windguru build their requests beyond the shared header-application fix.

## Decisions

### D1. Provider-declared identity, applied as an override

`OneChipDataProvider.requestHeaders(id)` returns a browser-like `User-Agent`, `Accept: text/html,application/xhtml+xml;q=0.9,*/*;q=0.8` and `Accept-Language: en`. `ProxyService` applies provider headers with **override** semantics (`setHeader`) instead of `addHeader`, so its default `Accept: application/json` is replaced rather than duplicated.

*Why this over the alternatives:* sending a user agent from the shared client (a global `HttpClients` default) would silently change what windy, neduet and windguru send for an unrelated bug; configuring identity per provider in `WindDataProviderConfig` is defensible but adds config surface for one provider — deferred as an open question. Overriding instead of appending avoids a request that advertises both JSON and HTML to an HTML-only upstream.

### D2. Column layout resolved from the header row

Parsing becomes two-phase. First a candidate table's header row is expanded through `colspan` into logical column slots and matched case-insensitively against the captions the page prints (`hh:mm`, `min`, `Avg`, `Gust`, `Dir`). A layout is accepted only if it resolves time, at least one speed column set, and a numeric direction column. Then data rows are read through that layout: cells outside the mapped slots (compass name, signal level, temperature, trailing empty cells) are ignored, short rows are skipped, and cell order within a row no longer matters beyond the mapped positions.

*Why:* the live header is 7 cells with colspans while rows have 9–10 cells; today's `CELL_DIRECTION = 7` / `MIN_CELLS_FOR_DATA_ROW = 8` constants are coincidences of the current markup. Scraping row text with regexes was rejected as more fragile and harder to diagnose than a resolved layout.

### D3. Table candidates with window-driven extension

The provider considers the recent table (`div#data_table`) first and the grouped table (`div#group_table`) as its fallback/extension candidate, in that order:

* If the recent table yields rows that already cover the requested `readingWindow`, only it is used — this keeps today's behavior for the configured 3600 s windows.
* If it yields no usable rows, or its span is narrower than the requested window (a ~3 h table cannot satisfy a 7200 s window), grouped rows are appended and merged by reconstructed timestamp, preferring the finer-grained recent row when both tables carry the same instant, then sorted ascending before windowing.
* A candidate table whose header cannot be resolved is not a table at all for our purposes: it contributes nothing and is counted in the diagnostics.

*Why:* reading only `#data_table` silently truncates every window wider than ~3 h, and a momentarily empty recent table currently produces "no data" even though the page still shows grouped history. Naively always concatenating both tables was rejected because their periods overlap, which would duplicate readings inside the returned set.

### D4. Parse stage returns an outcome, not just a list

`parseMeasurements` is replaced by a stage that returns the measurements **plus** a failure classification and counters (tables seen, candidate rows, valid rows). When the measurement list is empty the provider logs exactly one WARN carrying the reason code and context:

| Reason | Condition |
| --- | --- |
| `NO_BODY` | response null or blank |
| `EMPTY_PAGE_SHELL` | body parses to a document with no table elements (the 12-byte `</div></div>` case) |
| `NO_DATA_TABLE` | tables exist, but none resolves to a usable column layout |
| `NO_VALID_ROWS` | a usable layout was resolved, but no row produced valid values |
| `PARSE_ERROR` | an exception escaped parsing (today's generic error log, kept with the reason attached) |

All five still return `Try.success(List.of(SensorDataDTO.empty()))`, so the HTTP response stays `200` with one zero reading. Transport and non-2xx failures keep throwing from `ProxyService` and stay HTTP 500.

*Why:* the watch already renders a single zero reading as "No data", so changing the payload buys nothing for the user today while breaking older installed clients; the missing piece is a named reason in the log, which is what makes the production outage diagnosable. A richer client-visible status (empty array, an error header, or a separate health endpoint) is recorded as an open question because it needs a coordinated change in the watch repository.

### D5. Correction bounded by the evidence

The future guard's `Math.max(1, overshoot / 86_400) * 86_400` day shift is replaced by three bands, keeping the existing `currentTimeSeconds()` test seam:

* `overshoot ≤ FUTURE_TOLERANCE_SECONDS` (3600 s): no correction.
* tolerance `< overshoot < HALF_DAY_SECONDS` (43 200 s): treat as clock/zone skew — clamp readings newer than `now` down to `now`, log a WARN naming the skew, do **not** move the series by days.
* `overshoot ≥ HALF_DAY_SECONDS`: a date anchor is wrong; shift the whole series back by `ceil((overshoot − FUTURE_TOLERANCE) / 86_400)` whole days and log the shift.

Clamping can collapse two newest rows onto the same timestamp; windowing tolerates equal timestamps, so no ordering guarantee in the spec is weakened. The existing `neverReturnsAFutureReading` test pins "now" ~13 h before the newest row, which stays in the third band and keeps asserting a one-day shift; a new test covers the two-hour-skew band that today would corrupt fresh data.

### D6. Bounded timeouts on the shared upstream client

The `CloseableHttpClient` is built with explicit bounds: connect 10 s, response 30 s, connection-lease 10 s, taken from `wind-sensor.upstream-*`. `HttpClients.createDefault()` leaves the wait for a response unbounded (`RequestConfig.responseTimeout` and `ConnectionConfig.socketTimeout` are null; the only library defaults are 3 min for connect and 3 min to lease a pooled connection), so a 1chip.ru endpoint that accepts the TCP connection and then stalls (the behavior implied by the `522` timeouts seen from foreign proxies) pins a request thread and a client request indefinitely. Connect timeout is set on a `ConnectionConfig` attached to a pooling connection manager, because `RequestConfig.setConnectTimeout` is deprecated in HttpClient 5.6.

Automatic retries are disabled (`disableAutomaticRetries`). The default `DefaultHttpRequestRetryStrategy` re-executes an idempotent request once, after 1 s, when the upstream answers `503` or `429`, which was observed turning one `/sensor-data` request into two upstream calls — directly contradicting the "one client request → one upstream call" contract this change relies on, and doubling the load on an upstream whose willingness to answer is already in question.

*Why:* this is the one shared-plumbing change worth making, because it bounds the failure mode of every provider rather than only 1chip. Alternative considered: per-provider timeouts — rejected as extra config surface for a problem that is universal. Values are deliberately generous so a slow-but-successful Windy call cannot turn into a new 500. A retry policy remains an open question rather than a default inherited from a library.

### D7. Fixtures come from the live page, not from imagination

Test data moves under `src/test/resources/onechip/`: the captured `0264` and `0001` pages, the real empty-shell body (`</div></div>`), a grouped-table-only body, and a mutated-caption body used to prove the `NO_DATA_TABLE` classification. Live-capture fixtures are dated, so assertions pin "now" through the existing `currentTimeSeconds()` seam (or assert counts, ordering and value fidelity) rather than depending on wall-clock freshness — the current tests already follow that pattern for the future guard.

*Why:* the existing synthetic fixture encodes a `<th>`-based 9-cell header that 1chip.ru does not emit; keeping it as the only positive case is how this bug stayed invisible. The synthetic bodies are kept for the narrow behaviors they were written for (malformed rows, midnight rollback) because they are easier to read than a 30 KB page.

## Risks / Trade-offs

* **The upstream may simply refuse our egress.** If the server-side probe shows 1chip.ru serving an empty shell or timing out from Bratislava, no code in this repository restores those readings. → Mitigation: the probe is an explicit task, the new reason codes name the situation, and Windguru station `15693` (Dahab – Assalah) is already implemented on `master` as an alternative source once deployed. **Measured outcome (2026-11-17, after deploy):** the server-side probe returned `200` with `content-length: 0` — even the 12-byte shell a workstation gets for an inactive id is absent there, while the same URL served ~34 KB to a workstation at the same moment; Windguru `iapi.php` answers `403 {"return":"error","message":"forbidden"}` from that host. Neither mitigation is available from this egress, so the remaining options live outside this repository (a working alternative source in the spots config, or a different network exit on the host).
* **Presenting a browser user agent to a site we scrape.** It is the only way an HTML-only upstream can be read reliably, but it is also a claim about who we are. → Mitigation: one request per client request (no crawling, no retries), a single page per sensor, and a `text/html` accept that matches what we actually consume.
* **Header-driven mapping fails loudly instead of adapting.** A caption rename upstream yields `NO_DATA_TABLE` and no data rather than a best-effort guess. → Accepted deliberately: wrong wind numbers on a wingfoil watch are worse than absent ones, and the diagnostic states exactly what could not be resolved.
* **Merging grouped rows can coarsen a wide window.** A 7200 s window may return 15-minute grouped readings mixed with 3-minute ones. → Accepted; deduplication by timestamp keeps the finer row when both tables cover the same instant.
* **New timeouts could surface as new 500s** for an upstream that used to answer slowly. → Mitigated by generous bounds and by the fact that a stalled call is already unusable for the watch.

## Migration Plan

1. Land the change on `master` (no schema, no data migration — pure behavior).
2. Dispatch `Deploy to Server` so production runs it; until then production keeps its pre-`6de0cca` behavior regardless of what is merged.
3. Verify from the server (`curl -A <UA> https://1chip.ru/windt.php?id=0264 | wc -c`) and from a client (`POST /sensor-data` for `0264`/`0001`), then read the container log for either real readings or a named reason code.
4. Recorded outcome (2026-11-17): the reason was `NO_BODY` with `bodyLength=0`, not `EMPTY_PAGE_SHELL`. Switching the affected spots in `vladiksun/WindSensorConfig` to an alternative provider is still the planned step, but the substitute available on `master` is itself refused from that host (Windguru answers `403`), and the Windy mirror of a Dahab 1chip station publishes no data — so a source that actually answers from Bratislava has to be chosen first.
5. Rollback is redeploying the previous image; nothing persists between requests, so no data cleanup is involved.

## Open Questions

* Should request identity (`User-Agent`, `Accept-Language`) be a per-provider config key in `WindDataProviderConfig`, or is a provider constant enough for the only HTML upstream we have? Decidable after the server-side probe without changing the spec.
* Should "provider has no data" become visible to clients (empty array, response header, or a health endpoint)? Requires a coordinated change in the watch repository and its own capability spec.
* Do 1chip sensors outside Egypt need per-sensor time zones? Current spots are all `Africa/Cairo`; a new region would answer this.
* Is a bounded retry worth adding for transient upstream failures, given it interacts with how often watches poll?

