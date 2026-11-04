# Proposal

## Why

Windguru.cz hosts a large share of the community anemometers used at Red Sea spots — including Dahab – Assalah (`station/15693`, the sensor named "Assalah" in our spots config) — but the backend has no provider for it, so clients cannot query those sensors through `/sensor-data`. Windguru already publishes a per-station JSON endpoint (`/int/iapi.php`) that returns timed min/avg/gust readings with epoch-second timestamps and knots speeds, which maps one-to-one onto our existing `SensorDataDTO` contract, so coverage is cheap to add.

## What Changes

- Add a new provider implementation **`WindguruDataProvider`** with provider code / config key **`windguru`**, which calls Windguru's station history endpoint (`q=station_data&id_station=<id>&from=<ISO-UTC>&to=<ISO-UTC>&avg_minutes=<n>`) and normalizes its **columnar** JSON arrays (`unixtime[]`, `wind_avg[]`, `wind_max[]`, `wind_min[]`, `wind_direction[]`) into the same ascending, windowed readings every other provider produces.
- Add Windguru wire/normalization models: `WindguruStationData` (the columnar payload) and `WindguruMeasurement` (one normalized reading).
- **Extend the provider contract so a provider can declare upstream request headers.** Windguru answers `401 Unauthorized` for the data endpoints unless the request carries a `Referer` header, which today's `ProxyService` never sends. `WindDataProvider` gains a default `requestHeaders(sensorId)` hook (empty by default) and `ProxyService` applies it to the outbound call. Existing providers (Windy, Neduet, 1chip) keep their current behavior because the default adds nothing.
- Register the provider in `src/main/resources/application.yml` under `wind-sensor.wind-providers.windguru`.
- Add a provider unit test (`WindguruDataProviderTest`) with a captured payload fixture, and add windguru scenarios to `WindSensorControllerMockServerTest` (including one that asserts the `Referer` header actually reaches upstream).
- No public HTTP API change: the provider is selected by the existing `sensor.provider` field. No new dependencies (JSON only — no HTML parsing needed).

## Capabilities

### New Capabilities

- `windguru-wind-provider`: Fetches and normalizes timed wind readings for sensors hosted on Windguru station pages, including the upstream request-header behavior required for those calls to succeed and the handling of Windguru's gap/empty payloads.

### Modified Capabilities

<!-- None. `openspec/specs/` currently contains no capability specs, so there is nothing to modify.
     The `WindDataProvider` header hook is an internal contract extension; its observable effect
     (Windguru calls carry a Referer) is specified inside the new capability. -->

## Impact

- **New code:** `com.vb.wingfoil.provider.impl.WindguruDataProvider`, `com.vb.wingfoil.response.windguru.WindguruStationData`, `com.vb.wingfoil.response.windguru.WindguruMeasurement`; test class `WindguruDataProviderTest` + fixture under `src/test/resources`.
- **Touched shared plumbing:** `com.vb.wingfoil.provider.WindDataProvider` (new default method) and `com.vb.wingfoil.ProxyService` (applies provider headers to the outbound `HttpGet`). Request/response contracts of `/sensor-data`, `/spots-data*` and all existing providers are unchanged.
- **Configuration:** one new key `wind-sensor.wind-providers.windguru.url`; the sensor entries in the external spots config must use `"provider": "windguru"` with the numeric Windguru station id (e.g. `"15693"`).
- **External dependency / ops:** a new outbound dependency on `www.windguru.cz`. A request without a `Referer` fails with `401`; an unknown station id or a malformed time range fails with `400`, which the existing `ProxyService` + `GlobalExceptionHandler` path already reports as a failed upstream call (HTTP 500).
- **Dependencies:** none added — `micronaut-serde-jackson` and Apache HttpClient5 are already in use.
