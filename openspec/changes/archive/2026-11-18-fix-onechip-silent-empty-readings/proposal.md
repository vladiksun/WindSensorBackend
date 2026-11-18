# Proposal

## Why

Sensors configured with `"provider": "onechip"` (e.g. `0264` "Dahab – Assala 2" and `0001`) produce **no usable data** on the deployed backend: `POST /sensor-data` answers `200` with a single zero reading
`[{"windMax":0.0,"windAvg":0.0,"windMin":0.0,"windDirection":0.0,"timestamp":0}]`,
which the watch renders as "No data" — even though the very same `https://1chip.ru/windt.php?id=…` pages publish full histories that the current parser reads correctly from a workstation. `OneChipDataProvider` collapses every failure mode ("sensor offline", "upstream served an empty page shell", "page layout changed") into the same silent zero-filled fallback, so neither the client nor the operator can see *that* anything failed, let alone *what* failed.

### Evidence gathered while diagnosing

* Parsing the live pages for `0264` and `0001` with HEAD's exact logic yields **51 and 57** usable rows; a `readingWindow=3600, numberOfReadings=5` request would return five real readings (gust 11.6–12.5 m/s, dir 22–38°, newest ~12 min old). So parsing and windowing are not what breaks in production.
* The deployed backend (`https://vm67706.vpsone.xyz`, VPS in Bratislava, AS61424 eServer) returns the single zero reading for `0264`, `0001` **and** `0002` — exactly the response that a `</div></div>` body produces.
* 1chip.ru answers `HTTP 200` with a **12-byte body `</div></div>`** whenever it has nothing to render (verified today for id `0002`, which our Dahab config lists as "Lighthouse", and for the bogus id `9999`). An empty page shell is a normal upstream answer, not an error.
* Two foreign-hosted fetch proxies (`api.allorigins.win`, `api.codetabs.com`) both fail against 1chip.ru with `HTTP 522` (origin connection timeout), while the same URLs return full pages from this workstation: the host does not comfortably serve foreign datacenter egress, which is where the backend runs.
* The deployed image predates the windguru work: on production `provider: "windguru"` fails exactly like an unknown provider (`provider: "bogus"`) with `500`, while `WindguruDataProvider` has been in `master` since `6de0cca` (2026-11-04). Deployment is manual (`.github/workflows/deploy.yml` is `workflow_dispatch`), so production is not running current code.

## What Changes

* **The 1chip request stops looking like an API client.** `OneChipDataProvider` declares upstream request identity through the existing `WindDataProvider.requestHeaders(sensorId)` hook — a browser-like `User-Agent`, `Accept: text/html,application/xhtml+xml`, `Accept-Language`. Today `ProxyService` hard-codes `Accept: application/json` for *every* sensor call and appends provider headers, so an HTML-only upstream is asked for JSON and the client is unidentifiable; `ProxyService` will let a provider-supplied header **replace** its default instead of appending a second value.
* **Parsing binds to the page's real column contract instead of fixed cell indices.** The live page's header row is 7 `<td>` cells with colspans (`Gust` and `Dir` each span two columns), while data rows carry 9 or 10 cells depending on position; the current `CELL_DIRECTION = 7` / `MIN_CELLS_FOR_DATA_ROW = 8` constants work only by luck and silently produce wrong values (signal level, compass text, temperature) when a row shifts. The provider will map logical columns (`hh:mm`, `min`, `Avg, m/s`, `Gust, m/s`, `Dir, °`) from the header row with colspan expansion, and tolerate rows with missing or trailing cells.
* **Both history tables are read.** The page renders a recent table (`div#data_table`, roughly 3 h of ~3-minute rows) *and* an older grouped table (`div#group_table`, 15-minute groups). Today only `#data_table` is parsed, so any window wider than ~3 h silently truncates and a sensor whose recent table is momentarily empty returns nothing.
* **Failures are classified and reported.** Each unproductive fetch logs one WARN with a reason code — `NO_BODY`, `EMPTY_PAGE_SHELL`, `NO_DATA_TABLE`, `NO_VALID_ROWS`, `PARSE_ERROR` — including the URL, HTTP status, body length, tables seen and rows parsed, so "1chip is refusing us" becomes distinguishable from "this sensor is offline". The `200 + single zero reading` response shape stays unchanged for client compatibility.
* **Timestamp correction stops over-shifting.** The future guard shifts the whole series back by a whole day whenever the newest reconstructed timestamp exceeds `now` by more than one hour, which turns fresh data into ~22 h stale data on a two-hour clock or zone error. Whole-day roll-back will apply only when the overshoot clearly indicates a wrong date anchor; smaller skew is clamped and logged.
* **Tests are re-based on captured live pages** (real `0264` / `0001` pages, a real empty-shell body, a grouped-table-only body) instead of the synthetic fixture that currently encodes a page shape 1chip.ru does not produce.
* **Every upstream call is bounded and issued once.** `ProxyService` built its client with `HttpClients.createDefault()`, which applies no connect, response or lease timeout — an endpoint that accepts the connection and then stalls pins the request indefinitely — while HttpClient's default retry strategy silently re-executes a request after a `503` or `429`, turning one client request into several upstream calls. The shared client now carries configured timeouts and automatic retries disabled.
* **Operational verification is part of the change:** probe 1chip.ru from the production host, confirm which commit the running image was built from, redeploy (which also makes `windguru` available), and evaluate switching the Dahab spots to Windguru station `15693` as their primary source.

### Non-goals

* No change to the `/sensor-data` request or response contract, and no client/watch changes.
* No caching, rate limiting, or provider-fallback chains for upstream calls.
* No new outbound dependencies — jsoup and Apache HttpClient5 are already in use.

## Capabilities

### New Capabilities

* `onechip-wind-provider`: Behavior of the 1chip.ru HTML provider — upstream request identity, which page table and columns are read, how per-row timestamps are derived and corrected, and how unavailable or refused upstream data is reported.

### Modified Capabilities

<!-- None. `openspec/specs/` currently contains only `windguru-wind-provider`; the requirement sets
     archived in `2026-09-10-add-onechip-wind-provider` and `2026-09-21-fix-onechip-timestamp-derivation`
     were never synced into `openspec/specs/`. This change re-baselines them as a single capability
     spec: requirements that still hold are carried forward, the ones this diagnosis proved wrong
     (fixed cell indices, "any unparseable response is an empty reading") are replaced. -->

## Impact

* **Code:** `com.vb.wingfoil.provider.impl.OneChipDataProvider` (header-driven column mapping, table selection across `#data_table` / `#group_table`, failure classification, `requestHeaders`, bounded future guard); `com.vb.wingfoil.ProxyService` (a provider-supplied header must override the default `Accept` instead of duplicating it — no behavior change for windy, neduet or windguru); optionally `WindSensorConfig.WindDataProviderConfig` if request identity becomes per-provider config.
* **Tests:** `OneChipDataProviderTest` re-based on captured fixtures under `src/test/resources/onechip/`; `WindSensorControllerMockServerTest` scenarios asserting the outbound 1chip call carries the declared headers (and no duplicated `Accept`), that an empty-shell upstream still produces the compatible zero reading, and that a `503` upstream yields exactly one upstream call and keeps failing as an upstream error; `WindSensorControllerUpstreamTimeoutTest` for the stalled-upstream case.
* **API / configuration:** unchanged wire contract for `/sensor-data`; no new required config keys.
* **Ops:** none of this takes effect until the `Deploy to Server` workflow is dispatched; the change also requires a server-side reachability probe of 1chip.ru and, if refusal is confirmed, a `vladiksun/WindSensorConfig` edit moving those sensors to another provider (Windguru station `15693` for Dahab – Assalah).
* **Risk:** if the server-side probe shows that 1chip.ru refuses datacenter egress outright, no code change in this repository can restore those readings; the durable answer is a different upstream for those sensors or an egress change on the server. The hardening and diagnostics still pay off, because they turn an invisible outage into a named one.


