# Tasks

## 1. Rename the provider implementation without changing public selection

- [x] 1.1 Rename `WindguruDataProvider` and its source file to `WindguruSimpleHttpClientDataProvider`, keeping `NAME = "windguru"` unchanged, and verify `./gradlew -q compileJava` succeeds.
- [x] 1.2 Rename `WindguruDataProviderTest` and its file to `WindguruSimpleHttpClientDataProviderTest`, update imports/references in tests, and verify `./gradlew test --tests 'com.vb.wingfoil.provider.impl.WindguruSimpleHttpClientDataProviderTest'` passes existing URL-template and payload/windowing cases.
- [x] 1.3 Update current documentation references to the renamed implementation class (not archived OpenSpec history), then verify a source search finds no active production/test reference to `WindguruDataProvider` outside historical archives.

## 2. Add token wire models and deterministic token URL derivation

- [x] 2.1 Add narrow Windguru token response records that parse only `data.token` and optional `data.ttl` from the observed payload while ignoring `return`, `message`, and `wgk`, and verify a unit test parses the fixture shape and rejects missing or blank tokens without mapping reserved top-level keys.
- [x] 2.2 Add a provider helper that derives the token URL by splitting at the first `?`, removing any `%s` placeholder from the base path, and appending exactly `?q=token_refresh`; verify tests cover production-shaped URLs and local stub-shaped URLs such as `/api/windguru/%s?q=station_data` mapping to `/api/windguru/?q=token_refresh` without making network calls.

## 3. Mint and cache Windguru tokens inside the renamed provider

- [x] 3.1 Add a package-private constructor that accepts the effective token URL, an ObjectMapper or JSON parser dependency already available to the provider, a test-controlled clock, and (if needed) a bounded HTTP client, while keeping the public `@Inject` production constructor unambiguous for Micronaut; verify DI-based tests still construct the renamed bean.
- [x] 3.2 Implement lazy token acquisition from the derived token URL using a dedicated Apache HttpClient5 GET with `Accept: application/json`, the same configured upstream timeout bounds, no cookies/session state, and no automatic retries; accept only HTTP 2xx plus non-blank `data.token`, and verify loopback or fixture-based tests prove valid JSON produces a usable token while malformed or blank-token responses fail as token-acquisition failures.
- [x] 3.3 Implement an immutable cached token snapshot with expiry computed from upstream TTL minus a 60-second safety margin, capped at 900 seconds, floored at 1 second, defaulting missing or non-positive TTL to 900 seconds; verify tests with a test-controlled clock prove first request mints once, later requests reuse while valid, and expired snapshots refresh exactly once.
- [x] 3.4 Change `requestHeaders(sensorId)` to return both `Referer` and `X-WG-Token`, using the cached or freshly minted token, and throw an `AppException` with status `502` when no valid token can be obtained; verify provider unit tests assert station-specific Referer plus a non-empty token header, and assert header preparation fails before any station-data headers are returned.
- [x] 3.5 Update current Windguru rate-limit documentation with the 2026-11-19 `token_refresh` finding and this implementation choice, then verify the document names the required request headers and distinguishes this immediate fix from future official-API or governor work.

## 4. Propagate token-acquisition failures through `/sensor-data` before station data

- [x] 4.1 Adjust `ProxyService.fetchReadings` so URL building, provider header preparation, request construction, and HTTP execution are all inside the existing `Try.of(...)` failure path; verify existing non-Windguru response-cache and error-mapping tests still pass unchanged.
- [x] 4.2 Add a deterministic local stub test where the Windguru token endpoint returns a non-success status or invalid token JSON, then verify `/sensor-data` uses the existing sanitized upstream-failure mapping (expected `502` for provider token-acquisition failures), stores nothing in the response cache, and the stub records zero station-data requests.

## 5. Verify end-to-end request headers through the shared provider path

- [x] 5.1 Update MockServer Windguru tests to stub the derived token-refresh URL under the configured provider URL family (for `<endpoint>/api/windguru/%s?q=station_data`, expect `<endpoint>/api/windguru/?q=token_refresh`) and station data under the existing sensor path; verify the captured station-data request contains both `Referer` for the exact station id and a non-empty `X-WG-Token`, while successful `/sensor-data` response bodies remain unchanged.
- [x] 5.2 Add or update a deterministic stub-server test that does not rely on Docker or live Windguru endpoints; verify token refresh is requested with JSON-only headers and no cookie header, and station data reuses the minted token across sequential calls when within its cached validity window.

## 6. Integration verification

- [x] 6.1 Run targeted tests for renamed provider behavior (`./gradlew test --tests 'com.vb.wingfoil.provider.impl.WindguruSimpleHttpClientDataProviderTest'`) and verify all new token/header/cache cases pass without live Windguru network calls.
- [x] 6.2 Run the shared `/sensor-data` response-cache and upstream-error-mapping suites (`./gradlew test --tests 'com.vb.wingfoil.WindSensorControllerResponseCacheTest'`) and verify Windguru token failures are not cached, repeated requests attempt fresh provider work after failure, and successful Windguru responses still cache for 5 minutes with floating TTL. (Token-failure non-caching is asserted by `WindguruTokenAcquisitionFailureTest`; the five-minute TTL with its floating window band is provider-agnostic and asserted by `FloatingTtlCalculatorTest`.)
- [x] 6.3 Run Spotless (`./gradlew -q spotlessCheck`; if it fails run `./gradlew -q spotlessApply` then re-run check) and compile main/test sources (`./gradlew -q compileJava` and `./gradlew -q compileTestJava`); verify both pass.
- [x] 6.4 Run OpenSpec validation for this change (`openspec validate "fix-windguru-token-request-headers" --strict`) and verify it reports no spec delta or artifact issues.

## Workflow follow-up

- Apply the implementation only after an explicit apply request for this OpenSpec change.
- Archive `fix-windguru-token-request-headers` after implementation, validation, and any required review are complete.
- If Windguru later requires official API v1 credentials or a persistent governor, create separate changes for those capabilities rather than expanding this token-header fix.
