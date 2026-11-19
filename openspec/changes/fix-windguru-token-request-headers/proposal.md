# Proposal

## Why

The Windguru provider currently sends only a `Referer` header, but the 2026-11-19 production egress check shows that `https://www.windguru.cz/int/iapi.php?q=station_data` now returns `403` with `x-wg-token-state: missing` unless the request also carries a valid `X-WG-Token`. The same check proves that Windguru can be used again from the existing server without a browser or an official API key by minting a short-lived token from `q=token_refresh` and reusing it while valid.

## What Changes

- Rename the internal provider class `WindguruDataProvider` to `WindguruSimpleHttpClientDataProvider`, while keeping the public provider name, configuration key, sensor routing, URL construction, payload parsing, windowing, unit conversion, and `/sensor-data` response contract unchanged.
- Make the Windguru provider obtain a Windguru station-data token from the existing unofficial `q=token_refresh` endpoint with a plain HTTP client, without cookies, browser automation, or an official API key.
- Cache the minted token in process memory and refresh it only when absent or expired. The effective cached lifetime must remain shorter than the upstream token lifetime by a safety margin.
- Send both required headers on every Windguru `station_data` call:
  - `Referer`: the station page for the requested sensor id;
  - `X-WG-Token`: the currently valid minted token.
- Keep failure behavior safe: if no valid token can be obtained, the provider must not fabricate a reading or issue an unauthenticated station-data request that is known to fail as `403`; it reports an upstream authentication/transport failure through the existing `/sensor-data` error path.
- Update Windguru tests for the renamed class and for the new token/header behavior without contacting live Windguru endpoints from unit tests.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `windguru-wind-provider`: adds requirements for self-minted Windguru token acquisition, short-lived token reuse, mandatory station-data headers, and provider identity stability after the internal class rename.

## Impact

- Main code: `src/main/java/com/vb/wingfoil/provider/impl/WindguruDataProvider.java` becomes `WindguruSimpleHttpClientDataProvider.java`; private token response/cache helpers may be added in the same provider area.
- Shared request path: no public `/sensor-data`, controller, cache key, `X-Cache`, windowing, or unit-conversion behavior should change. Provider-declared headers continue to flow through `ProxyService`; its internal failure wrapping may need to include provider header preparation so token-acquisition failures are logged and mapped consistently.
- Configuration: existing `wind-sensor.wind-providers.windguru.url` remains the station-data endpoint; token acquisition can be derived from that endpoint unless a later implementation chooses an explicit token URL override.
- Tests: `WindguruDataProviderTest` and any MockServer/header assertions must be renamed or updated to expect both `Referer` and `X-WG-Token`, using local fixtures or local upstream stubs rather than live Windguru calls.
- Dependencies: no new runtime dependency is expected; Apache HttpClient5 and Micronaut serde are already present.
