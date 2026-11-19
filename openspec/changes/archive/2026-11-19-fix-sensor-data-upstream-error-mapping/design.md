# Design

## Context

See `proposal.md` for motivation. The current request path is:

1. `WindSensorController.getSensorData` calls `ProxyService.requestTimedReadings(...).get()`.
2. A cache miss runs through `ProxyService.fetchAndStore`, which delegates to `SensorDataSingleFlight`.
3. `ProxyService.fetchReadings` performs the provider call and `ProxyService.handleResponse` currently throws a plain `java.io.IOException` whose message contains the full upstream URL for any non-2xx status.
4. That throwable reaches `GlobalExceptionHandler`, which only maps `TileProxyException` to its own status; every other failure becomes `500 Internal Server Error` and its raw exception message is rendered into the JSON error body.

The response-cache test suite currently asserts the old behavior: a provider `503` produces a generic `500` whose message contains "Upstream call failed with status 503". That assertion must change together with the implementation.

## Goals / Non-Goals

**Goals:**

- Preserve upstream HTTP error codes for `/sensor-data` provider failures, including `403 -> 403`.
- Make transport-level provider failures explicit as gateway failures rather than generic backend bugs.
- Keep client-visible failure messages short and free of provider URLs, query strings, raw bodies, and internal cause details.
- Preserve the original failure in logs and as the cause of the application-facing exception.
- Keep coalescing behavior intact: every request that joined one upstream call receives the same mapped failure.

**Non-Goals:**

- Do not change successful `/sensor-data` payloads, cache keys, TTL behavior, or `X-Cache` reporting.
- Do not change `/spots-data`, `/spots-data-dahab`, tile endpoints, provider retry policy, provider headers, or provider parsing rules.
- Do not add caching of failed responses.
- Do not attempt to bypass upstream blocking or rate limiting; this change only reports those failures correctly.

## Decisions

### 1. Add a shared application exception carrying an HTTP status

Create `com.vb.wingfoil.AppException extends RuntimeException` with:

- the HTTP status code intended for the client response;
- a short safe message suitable for JSON error rendering;
- the original failure as the standard exception cause.

The handler can pattern-match this exception and use its carried status directly, following the existing `TileProxyException` precedent without reusing tile-specific semantics for sensor-data failures.

**Alternatives considered:**

- Reuse `TileProxyException`: rejected because it describes tile-proxy failures and would make cross-feature error handling less clear.
- Throw Micronaut HTTP exceptions from `ProxyService`: rejected because the service layer should return Vavr `Try` failures rather than framework response types.

### 2. Represent known upstream statuses as a typed IOException before mapping

`ProxyService.handleResponse` should throw an internal exception that extends `java.io.IOException` and carries the numeric upstream status. Its own message may retain the full internal URL for server-side diagnostics because it will become the cause, not the client-facing message.

This lets the failure mapper distinguish:

- "provider answered with HTTP error status X" — propagate X;
- "transport call failed before any response status was available" — map to `502 Bad Gateway`.

**Alternatives considered:**

- Parse the current plain `IOException` message for a status code: rejected because it is fragile and couples exception semantics to log text.
- Throw the public application exception directly from response handling: possible, but keeping raw IOExceptions until service-level normalization matches the requested boundary better and preserves the original diagnostic cause cleanly.

### 3. Normalize failures before single-flight propagation

Apply the mapping inside the fetch supplier used by `SensorDataSingleFlight`, before that result is completed into the shared future. Do the same for the cache-disabled path so both request paths behave identically.

This ensures coalesced waiters receive the identical already-mapped failure instead of each waiter wrapping the same raw failure into a separate exception instance.

Mapping rules:

- already-mapped application failures pass through unchanged;
- typed upstream-status IOExceptions map to the same numeric HTTP status;
- remaining `IOException` failures from the provider call map to `502 Bad Gateway`;
- non-upstream validation failures such as null provider, blank sensor id, or unknown provider remain out of scope and keep their current behavior.

### 4. Sanitize only the client-facing message

`GlobalExceptionHandler` should continue to build a `JsonError` from the application exception's safe message and link it to the request URI. The status comes from the carried HTTP code. The raw cause remains available in the exception chain for logs, but it must not be rendered into the response body.

Suggested client messages:

- provider returned an HTTP error status: `Sensor data provider returned an error`;
- transport failure without a status: `Sensor data provider is unavailable`.

These strings intentionally do not include the numeric status; the HTTP response itself carries the exact code.

### 5. Update tests for the new externally visible contract

The existing stub upstream should gain a forbidden sensor id that answers `403`. Existing assertions need these updates:

- provider `503`: expected client status changes from `500` to `503`, and the message no longer contains internal fetch details;
- stalled provider timeout: expected client status changes from `500` to `502`;
- new `403` case: response status is `403`, JSON message is short, and no provider URL/query text appears in the body.

A service-level test should assert that the failed `Try` carries an application exception whose cause chain still contains the original upstream failure. This verifies the diagnostic requirement without asserting private log output.

## Risks / Trade-offs

- **Existing clients may treat all `/sensor-data` failures as `500`** → Mitigation: document this as a breaking error-contract fix and update tests before deployment.
- **Transport failures mapped to `502` may be read as backend outage** → Mitigation: server logs distinguish provider connect/response timeouts from local application faults through preserved causes.
- **Propagating upstream `403` reveals that the provider blocked this host** → Accepted: that is the requested behavior, while URLs and query strings remain hidden from clients.
- **Failure mapping too broad could swallow unrelated programming errors** → Mitigation: map only known application failures and upstream `IOException`s; leave existing validation failures unchanged.

## Migration Plan

1. Implement the exception type and service normalization in one backend change.
2. Update `/sensor-data` failure tests and run targeted suites before full build checks.
3. Deploy normally; no data or cache migration is needed.
4. In production, verify a known blocked Windguru request returns HTTP `403` with a sanitized body while logs still show the original upstream exception stack.

Rollback is a straight revert of the service and handler changes. The response cache itself does not need to be disabled or flushed.
