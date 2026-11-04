# Tasks

Reference: a live probe of station 15693 on 2026-11-04 confirmed the endpoint, the mandatory
`Referer`, knots units, epoch-second `unixtime`, `null` gaps inside numeric arrays, and `[]` for an
empty range (details in design.md). MockServer cases need Docker (`@EnabledIfTrue(NEEDS_DOCKER)`);
per AGENTS.md run Gradle through the wrapper, `-q` for build/spotless only, **no** `-q` for tests.

## Implementation notes (deviations found while applying the tasks)

These are wording corrections to the plan above; none change specified behaviour:

1. **1.1** snake_case wire keys are mapped with `@Serdeable(naming = SnakeCaseStrategy.class)` — the project's
   existing convention (`WindyStationApiResponse`) — not per-field `@JsonProperty` (which no model in this repo uses).
2. **3.1** the provider is annotated `@Singleton` only, matching `WindyDataProvider`/`OneChipDataProvider`; the bean
   name comes from the config key, and the interface method is `getName()`, not `getProviderName()`.
3. **3.5** the real signature is `extractTimedReadings(String sensorId, String response, int, int)` (it always took
   `sensorId`), and delegation needs the mapper/interval-reader lambdas: `buildTimedReadings(data, rw, nr, this::getLastReading, …getReadingsByInterval…)`. Recovery is the inline `.recover(throwable -> …)` used by `WindyDataProvider`, not a `recover(String, Exception)` helper.
4. **4.2** only `wind-sensor.wind-providers.windguru.url` is injected; `@EachProperty` derives the provider name from
   the key, so no `.id` property exists. The mock template is `<endpoint>/api/windguru/%s?q=station_data` — the `?`
   is required so the provider's appended `&from=&to=` lands in the query string rather than the path.
5. **4.6** the client-visible body for an upstream failure is the existing 500 with the *cause* message
   (`"Upstream call failed with status 401 …"`), not `"Internal server error"` (that body only appears when the
   exception carries no message, e.g. the unknown-provider case). The spec delta already described the correct shape.
6. **2.3/2.4** the stub provider lives in `WindSensorControllerMockServerTest.HeaderProbeProvider`
   (`@Singleton @Requires(property = "spec.name")`, config key `probe`) and is exercised on `/api/probe/1`; there was
   no existing 1chip wiring in that class to reuse. Absence of extra headers is asserted with
   `verify(…withHeader("X-Test-Header", "sent"), exactly(0))`.

## 1. Wire and normalization models

- [x] 1.1 Create `com.vb.wingfoil.response.windguru.WindguruStationData` as a `@Serdeable` record exposing only the columns we consume — `List<Long> unixtime`, `List<Float> windAvg`, `List<Float> windMax`, `List<Float> windMin`, `List<Float> windDirection` — with `@JsonProperty` names matching the wire keys (`unixtime`, `wind_avg`, `wind_max`, `wind_min`, `wind_direction`) so unknown members (`temperature`, `mslp`, `rh`, `gustiness`, `sunrise`, `sunset`, `startstamp`, `endstamp`, `tzoffset`) are ignored and in-array `null`s deserialize as `null` elements.
  *Verify:* `./gradlew -q compileJava`; `./gradlew -q spotlessCheck` (run `spotlessApply` if it fails).

- [x] 1.2 Create `com.vb.wingfoil.response.windguru.WindguruMeasurement` as a plain record `(float min, float avg, float gust, float dir, long timestamp)` mirroring `OneChipMeasurement`/`WindyMeasurement` accessor style, built by unzipping columns rather than deserialized.
  *Verify:* `./gradlew -q compileJava`; both types lint clean via `./gradlew -q spotlessCheck`.

## 2. Provider request-header hook (shared plumbing)

- [x] 2.1 Add to `com.vb.wingfoil.provider.WindDataProvider` a `default Map<String, String> requestHeaders(String sensorId)` returning `Map.of()`, documented as "extra headers this provider requires on the upstream call; empty by default". No existing provider overrides it.
  *Verify:* `./gradlew -q compileJava`; `jetbrains-openide__lint_files` reports no new warnings for `WindDataProvider.java`.

- [x] 2.2 In `ProxyService.requestTimedReadings`, apply `provider.requestHeaders(sensorId)` to the `HttpGet` built by `buildRequest` (null-safe, skip when empty), keeping the existing `Accept: application/json` header untouched.
  *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.WindSensorControllerMockServerTest'` — every pre-existing case still passes (Docker required).

- [x] 2.3 Add `requestHeadersAreSentUpstream` to `WindSensorControllerMockServerTest`: build the stub provider with an overridden `requestHeaders` returning `{"X-Test-Header": "sent"}`, and make the MockServer expectation require that header on `/api/onechip/1`, so a missing application step fails the test.
  *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.WindSensorControllerMockServerTest.requestHeadersAreSentUpstream'`.

- [x] 2.4 Add `providersWithoutExtraHeadersSendOnlyAccept` to the same class: assert a provider that does not override the hook still reaches upstream successfully with the default expectation (`withHeader("Accept", "application/json")`) and no additional custom header on the recorded request.
## 3. Provider implementation and unit tests

- [x] 3.1 Create `com.vb.wingfoil.provider.impl.WindguruDataProvider extends BaseWindyDataProvider<WindguruMeasurement>` annotated `@Singleton @Named(WindguruDataProvider.NAME)`, with `public static final String NAME = "windguru"`, `LOOK_BACK_SECONDS = 86_400`, and a constructor `(WindDataProviderConfig config, ObjectMapper objectMapper)` delegating to `super(config, objectMapper)`; `getProviderName()` returns `NAME`.
  *Verify:* `./gradlew -q compileJava`.

- [x] 3.2 Override `getCallUrl(String sensorId)`: take the configured template (`getUrl().formatted(sensorId)` — exactly one `%s`) and append `&from=<ISO-UTC>&to=<ISO-UTC>` built from `Instant.now()` minus `LOOK_BACK_SECONDS` and `Instant.now()` rendered with `DateTimeFormatter.ISO_INSTANT`; override `getLastReading` to read `getCallUrl`.
  *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.WindguruDataProviderTest.getCallUrlContainsStationIdAndUtcRange'` asserts the URL contains `q=station_data`, `id_station=15693`, `avg_minutes=1`, both `from`/`to` in ISO-UTC form, and `from` strictly before `to`.

- [x] 3.3 Override `requestHeaders(String sensorId)` to return `Map.of("Referer", "https://www.windguru.cz/station/" + sensorId)` — the header Windguru requires (401 without it).
  *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.WindguruDataProviderTest.requestHeadersCarryStationPageReferer'` asserts exactly that key/value pair for sensor `15693`.

- [x] 3.4 Add fixture `src/test/resources/windguru/station-data.json`: the captured live `station_data` payload for station 15693, trimmed to a handful of rows but **keeping** the untouched extra columns (`temperature`, `mslp`, `rh`, `sunrise`, `tzoffset`) and at least one index whose `wind_avg` is `null`, so unknown-member handling and gap handling are both exercised.
  *Verify:* fixture loads in the test classpath (used by 3.5 assertions; no assertion can pass without it).

- [x] 3.5 Override `extractTimedReadings(String response, int readingWindowSeconds, int numberOfReadings)`: guard the payload shape first (blank body, or first non-whitespace char `[` → `List.of(SensorDataDTO.empty())` logged at debug), then deserialize `WindguruStationData`, unzip the columns index-by-index into `WindguruMeasurement` skipping any index where `unixtime` or one of the four wind values is `null`, and delegate to `buildTimedReadings(measurements, readingWindowSeconds, numberOfReadings)`.
  *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.WindguruDataProviderTest'` covering `parsesColumnarPayloadIntoAscendingReadings` (fixture values match positionally, timestamps ascending), `skipsRowsWithMissingValues`, `emptyArrayBodyReturnsSingleEmptyReading`, `blankBodyReturnsSingleEmptyReading`.

- [x] 3.6 Override `mapToDTO(WindguruMeasurement)` as `new SensorDataDTO(gust(), avg(), min(), dir(), timestamp())` and `getLastReading(List<WindguruMeasurement>)` as the highest-`timestamp()` element, matching the Neduet/1chip accessor ordering convention; `recover(String msg, Exception e)` logs and returns a single empty reading.
  *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.WindguruDataProviderTest.mapsPublishedFieldsWithoutConversion'`, plus `zeroWindowAndZeroCountReturnLatestOnly` and `windowedRequestHonoursNumberOfReadings` (3600 s / 5 readings against a fixture-shaped series).

## 4. Configuration and controller-level integration tests

- [x] 4.1 Add to `src/main/resources/application.yml`, under `wind-sensor.wind-providers`, the entry `windguru: url: https://www.windguru.cz/int/iapi.php?q=station_data&id_station=%s&avg_minutes=1&graph_info=1` (single `%s`; static params live in config per design decision 4). Leave `application-test.yml` untouched — integration cases inject their own provider properties.
  *Verify:* `./gradlew -q compileJava` plus the live smoke check in 5.5, which is what proves this exact URL works end to end.

- [x] 4.2 Add `WINDGURU_PROVIDER_PROPS` to `WindSensorControllerMockServerTest` mapping `wind-sensor.wind-providers.windguru.id=windguru` and `...windguru.url=<mockServerEndpoint>/api/windguru/%s`, mirroring the existing onechip property block.
  *Verify:* `./gradlew -q compileTestJava`.

- [x] 4.3 Add `windguruProviderReturnsNormalizedReadings`: request with `"provider": "windguru"`, `"id": "15693"` (mirroring the Assalah entry), respond with the fixture payload, assert min/avg/gust/direction/timestamp of the returned readings and that they are ascending.
  *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.WindSensorControllerMockServerTest.windguruProviderReturnsNormalizedReadings'`.

- [x] 4.4 Add `windguruCallCarriesRefererHeader`: make the MockServer expectation require `withHeader("Referer", "https://www.windguru.cz/station/15693")` on `GET /api/windguru/15693`, so the test fails if `ProxyService` stops applying provider headers or the provider drops the referer Windguru demands.
  *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.WindSensorControllerMockServerTest.windguruCallCarriesRefererHeader'`.

- [x] 4.5 Add `windguruGapsAndNoDataResponsesAreHandled`: two cases in one test — a payload containing `null` entries returns only complete readings, and an upstream `[]` body returns a single empty reading (`200`, no exception).
  *Verify:* `./gradlew :test --tests 'com.vb.wingfoil.WindSensorControllerMockServerTest.windguruGapsAndNoDataResponsesAreHandled'`.

- [x] 4.6 Add `windguruUpstreamFailureIsReported`: respond `401` (the no-Referer failure mode) and assert the client-visible result is still the existing generic failure (`message: "Internal server error"`, logged cause `Upstream call failed with status 401`).
## 5. Integration checks

- [x] 5.1 Formatting gate: `./gradlew -q spotlessCheck`; if it fails, `./gradlew -q spotlessApply` and re-run until clean.
  *Verify:* `./gradlew -q spotlessCheck` exits 0.

- [x] 5.2 Compile gate for both source sets: `./gradlew -q compileJava` then `./gradlew -q compileTestJava`.
  *Verify:* both exit 0 with no new warnings from the added classes (`jetbrains-openide__lint_files` on `WindguruDataProvider.java`, `WindguruStationData.java`, `WindguruMeasurement.java`, `ProxyService.java`, `WindDataProvider.java`).

- [x] 5.3 Targeted test gate (no `-q`, Docker needed for the MockServer class): `./gradlew :test --tests 'com.vb.wingfoil.provider.impl.WindguruDataProviderTest'` then `./gradlew :test --tests 'com.vb.wingfoil.WindSensorControllerMockServerTest'`.
  *Verify:* all new windguru cases pass **and** every pre-existing case in `WindSensorControllerMockServerTest` (onechip, spots-data) still passes.

- [x] 5.4 Live smoke check of the exact configured endpoint, proving the URL template and header are right: request `https://www.windguru.cz/int/iapi.php?q=station_data&id_station=15693&avg_minutes=1&graph_info=1&from=<now-24h>&to=<now>` with header `Referer: https://www.windguru.cz/station/15693`.
  *Verify:* HTTP 200, body is a JSON object with equal-length `unixtime`/`wind_avg` arrays and at least one non-null wind value; the same request without the `Referer` header returns 401 (documents why the hook exists).

- [x] 5.5 Spec gate: `openspec validate add-windguru-wind-provider --strict`.
  *Verify:* validation reports the change is valid.

- [x] 5.6 Memory-bank sync for the new provider: add a `windguru` row to the providers/provider-configuration tables in `memory-bank/techContext.md` and `memory-bank/systemPatterns.md`, record the completed work and the live-endpoint findings (Referer requirement, knots units, epoch timestamps, `null` gaps, `[]` empty range) in `memory-bank/progress.md`, and update `memory-bank/activeContext.md` with the new focus plus the deferred items from design.md's Open Questions.
  *Verify:* grep for `windguru` in all four memory-bank files; stage new files with `git add` (no commit).


