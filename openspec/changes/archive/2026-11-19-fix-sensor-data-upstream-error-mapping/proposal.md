# Proposal

## Why

A Windguru `403` currently reaches `POST /sensor-data` as a generic server error whose JSON message contains the full upstream URL. This makes provider failures indistinguishable from backend bugs and leaks internal request details to clients even though the correct upstream status is already known in the service layer.

## What Changes

- Introduce an application exception that carries the HTTP status intended for the client response, preserves the original failure as its cause, and has a short safe message.
- Translate upstream provider failures in the `/sensor-data` fetch path before they propagate to `WindSensorController.getSensorData` and `GlobalExceptionHandler`:
  - when the provider answered with an HTTP error status, return that same status (for example `403 -> 403`, `503 -> 503`);
  - when no upstream HTTP status is available because of a connect, response, pool-lease, or single-flight timeout, return `502 Bad Gateway`.
- Render client errors with the mapped status and only a short generic description; remove provider URLs, query strings, raw upstream payload text, and internal cause details from the client-visible message.
- Keep full original exception details in server logs by making them the cause of the application exception.
- Keep successful `/sensor-data` responses, cache identity, caching behavior, failure non-caching, and `X-Cache: HIT|MISS` unchanged.
- **BREAKING:** failed `/sensor-data` upstream calls no longer return a generic `500` with an internal message; known provider error statuses are propagated exactly, and transport failures become `502`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sensor-data-response-cache`: the requirements for failed cache-miss requests change from "same error response as today" to explicit HTTP status mapping, sanitized client messages, and preserved internal diagnostic causes.

## Impact

- Service code: `com.vb.wingfoil.ProxyService` upstream response handling, failure normalization in cached and cache-disabled fetch paths, and logging of the original cause.
- Exception API: new shared application exception carrying an HTTP status and cause.
- Error rendering: `com.vb.wingfoil.GlobalExceptionHandler` maps the new exception to its carried status while preserving current tile-proxy behavior.
- Tests: existing `/sensor-data` failure assertions must be updated from internal-message `500` responses to sanitized mapped statuses; add coverage for `403`, `503`, transport timeout, and coalesced failures.
- No new runtime dependencies are required.
