## Why

The WindSensor backend currently supports only JSON-based providers (Windy, Neduet). Many community anemometers publish their data exclusively through 1chip.ru's server-side-rendered HTML page — there is no REST API. Users of these sensors cannot retrieve their data through the app. Adding a 1chip.ru provider extends coverage to these sensors by fetching and parsing the HTML page into the same normalized readings every other provider produces.

## What Changes

- Add a new wind data provider implementation (`OneChipDataProvider`) that fetches `https://1chip.ru/windt.php?id=<sensorId>` and parses the returned HTML into normalized `SensorDataDTO` readings (min / avg / max-gust wind speed, numeric direction in degrees, timestamp).
- Introduce an HTML-parsing dependency (Jsoup), since the current stack has no HTML parser.
- Add a per-sensor measurement model for 1chip.ru (`OneChipMeasurement`).
- Register the provider under the existing `wind-sensor.wind-providers` configuration block in `application.yml` (key `onechip`).
- No changes to the public HTTP API contract; the new provider is selected via the existing `provider` field on a sensor.

> **Naming note:** Java identifiers cannot start with a digit, so the requested class name `1ChipDataProvider` is implemented as **`OneChipDataProvider`**. The externally visible provider name / config key is **`onechip`** (a client references it as `provider: onechip` in spots config).

## Capabilities

### New Capabilities

- `onechip-wind-provider`: Fetch and normalize wind readings for sensors hosted on 1chip.ru by parsing its server-side-rendered HTML page.

### Modified Capabilities

<!-- None. Existing provider behavior (Windy, Neduet) is unchanged. -->

## Impact

- **New code:** `com.vb.wingfoil.provider.impl.OneChipDataProvider`, `com.vb.wingfoil.response.onechip.OneChipMeasurement`.
- **Dependency:** add Jsoup (`org.jsoup:jsoup`) to `build.gradle` using an inline coordinate, matching the existing convention (no Gradle version catalog is present).
- **Configuration:** `src/main/resources/application.yml` → `wind-sensor.wind-providers.onechip.url`.
- **Reused, unchanged plumbing:** `ProxyService` provider registry, `BaseWindyDataProvider` timed-readings reduction, `WindDataProviderConfig` (`@EachProperty("wind-providers")`). No controller or API changes.
- **Spots config:** sensors using this provider must set `provider: onechip` and the 1chip.ru sensor `id`.
