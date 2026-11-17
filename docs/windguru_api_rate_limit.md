# Windguru API rate-limit analysis

Status: research findings for the planned `fix-windguru-upstream-rate-limiting` change.  
Date: 2026-11-17.  
Scope: Windguru station-data integration, especially station `15693`.

## Executive summary

The best sustainable solution is not to keep polling the unofficial Windguru page endpoint:

```text
https://www.windguru.cz/int/iapi.php?q=station_data...
```

Instead, use Windguru’s official authenticated API v1 as the primary upstream and wrap it with a governor/cache/coalescing layer. The governor remains useful for both the official API and any temporary scraping fallback, but it is not a substitute for using the officially allowed access path.

## Current production evidence

Recent remote Docker logs show repeated Windguru failures for station `15693`:

- all observed upstream failures are HTTP `403`;
- requests occur roughly every 12 seconds during active polling;
- there is no local throttling, cache coalescing, cooldown, or backoff around the current provider call;
- each client `/sensor-data` request currently causes a fresh full Windguru history fetch.

A controlled request from the affected remote server returns:

```http
HTTP/2 403
retry-after: 343781
content-type: application/json

{"return":"error","message":"forbidden"}
```

That `Retry-After` value is approximately:

```text
343,781 seconds = 3 days, 23 hours, 29 minutes, 41 seconds
```

This indicates the current production block is not a short request-per-minute limit. It looks like an IP-level bot/anti-abuse cooldown against the unofficial scraping endpoint.

## Windguru has two different access surfaces

### 1. Unofficial website data endpoint

The backend currently uses:

```text
https://www.windguru.cz/int/iapi.php?q=station_data&id_station=%s&avg_minutes=1&graph_info=1
```

This endpoint is used by Windguru station pages, but it is not documented as a public API. There is no published exact numeric request-per-minute boundary for arbitrary backend clients.

Windguru’s `robots.txt` publishes crawler guidance such as:

```text
User-agent: Yandex
Crawl-delay: 60

User-agent: bingbot
Crawl-delay: 60
```

That suggests a conservative public pacing target of at least 60 seconds for crawlers, but it is not a reliable API rate-limit contract.

### 2. Official Windguru API v1

Windguru now exposes an official public beta API:

- API docs: `https://api.windguru.cz/docs/`
- OpenAPI spec: `https://api.windguru.cz/v1/openapi.json`
- plan overview: `https://www.windguru.cz/help.php?sec=api`
- API terms: `https://www.windguru.cz/help.php?sec=apiterms`

The official API has documented authentication, rate limits, quota behavior, cache TTLs, and error semantics.

## Official Windguru API boundaries

### Authentication

Official API requests require a key in headers:

```http
Authorization: Bearer wg_xxxxxxxxxxxxxxxx
```

or:

```http
X-API-Key: wg_xxxxxxxxxxxxxxxx
```

The key must not be placed in the query string.

### Published plan limits

| Plan | Requests/min | Locations day/month | Stations day/month | Station history | Notes |
|---|---:|---:|---:|---|---|
| Developer | 30/min | 100 / 300 | 100 / 300 | 7 days per request, last 30 days | Free, test data only |
| Personal | 30/min | 10 / 40 | 3 / 15 | 7 days per request, last 30 days | Included in Windguru PRO |
| Project | 60/min | 30 / 150 | 10 / 50 | 30 days per request, last 30 days | Paid separately, about €49/year |
| Project+ | 120/min | 50 / 200 | 20 / 80 | 30 days per request, last 90 days | Paid separately, about €99/year |

Station quotas count different stations per day/month. Repeated requests for a station already used in the same period should not consume a new station slot every time, although requests still affect rate limits and fair-use/cost usage.

### Relevant official endpoints

For historical station readings:

```http
GET https://api.windguru.cz/v1/stations/{id}/data?from=...&to=...&step=10m
```

Parameters:

- `from` — required, date or ISO 8601 datetime; without timezone it is UTC.
- `to` — optional, defaults to now.
- `step` — `1h`, `10m`, or `raw`.
  - `1h` is cheapest.
  - `10m` and `raw` are full-resolution and more expensive.
- `vars` — optional variable filter such as:
  - `wind_avg`
  - `wind_max`
  - `wind_min`
  - `wind_direction`

For current station readings:

```http
GET https://api.windguru.cz/v1/stations/{id}/current?avg=10
```

### Cache and conditional requests

The official API documents:

- `/v1/stations/{id}/data` responses are cached for 300 seconds.
- `/v1/stations/{id}/current` responses are cached for 30 seconds.
- cacheable responses can expose `Cache-Control` and `ETag`.
- cached responses cost less than fresh uncached responses.

For this backend, a 300-second governor/cache TTL is aligned with the official station-history cache behavior.

### Rate-limit and quota headers

The official API documents headers including:

```text
X-RateLimit-Limit
X-RateLimit-Remaining
X-RateLimit-Reset
X-Quota-Day-Remaining
X-Quota-Month-Remaining
X-Stations-Limit
X-Stations-Remaining
X-Stations-Window
X-Cost
X-Cache
ETag
Cache-Control
Retry-After
```

Overlimit responses can be:

```http
429 rate_limited
429 quota_exceeded
```

with `Retry-After`.

## Compliance findings

Windguru’s general terms say automated downloading, scripts, scrapers, and bots are not allowed and are blocked. The official permitted ways to use Windguru data in other programs are widgets or the official API under their own terms.

Windguru’s API terms allow API use for personal projects, automation, internal tools, and apps of businesses whose main product is something else. However, they prohibit using the data in a service whose main content is weather forecasts, wind forecasts, weather station data, or a directory of spots, except for data from your own weather stations.

This means the current scraping endpoint is not just rate-limited; it is also outside the officially allowed access path. The official API is the correct technical route, but public deployment still requires checking that WindSensor’s main product is not effectively a wind-station-data service. If it is, explicit permission from Windguru or another authorized data source should be obtained before public use.


## Recommended solution

Use a two-layer solution.

### Layer 1: emergency governor/cache for the current provider

This is the immediate mitigation that can be implemented without an API key.

Required behavior:

- key governor state by `provider:sensorId`, for example `windguru:15693`;
- coalesce concurrent identical requests;
- enforce a minimum upstream interval, at least 60 seconds for Windguru scraping endpoints;
- cache the last successful raw upstream response;
- parse upstream `Retry-After` when present;
- do not contact upstream again until cooldown expires;
- serve stale cached data only within a bounded maximum age;
- return HTTP `503` with `Retry-After` when no usable cache exists.

Important: do not hard-code only a small fixed cooldown if Windguru sends a long `Retry-After`. The current production response contains a `Retry-After` of about 4 days, so the governor must honor upstream cooldown metadata when available.

### Layer 2: official Windguru API provider as primary production upstream

The proper long-term route is an authenticated official API adapter.

Example request shape:

```http
GET https://api.windguru.cz/v1/stations/15693/data?from=2026-11-17T14:00:00Z&to=2026-11-17T16:00:00Z&step=10m
Authorization: Bearer ${WINDGURU_API_KEY}
Accept: application/json
User-Agent: WindSensorBackend/1.0 (+https://github.com/vladiksun/WindSensor; contact: vladiksun@gmail.com)
```

For current live readings, a cheaper endpoint can be used:

```http
GET https://api.windguru.cz/v1/stations/15693/current?avg=10
Authorization: Bearer ${WINDGURU_API_KEY}
Accept: application/json
User-Agent: WindSensorBackend/1.0 (+https://github.com/vladiksun/WindSensor; contact: vladiksun@gmail.com)
```

For historical readings, prefer `step=1h` for long windows and `step=10m` when higher resolution is needed. Use `raw` only when it is genuinely required because it is more expensive.

## Suggested governor configuration

A reasonable default configuration for the planned change is:

```yaml
wind-sensor:
  wind-providers:
    windguru:
      enabled: true
      min-upstream-interval-seconds: 60
      response-cache-ttl-seconds: 300
      max-stale-age-seconds: 900
      rate-limit-cooldown-seconds: 120
      respect-upstream-retry-after: true
```

These values are conservative and practical:

- `min-upstream-interval-seconds: 60` avoids aggressive polling.
- `response-cache-ttl-seconds: 300` matches the official station-history cache TTL.
- `max-stale-age-seconds: 900` allows short upstream outages without serving very old wind data.
- `rate-limit-cooldown-seconds: 120` is a fallback when no upstream `Retry-After` exists.
- `respect-upstream-retry-after: true` prevents retrying while Windguru explicitly says the client must wait.

## Decision matrix

| Approach | Pros | Cons | Verdict |
|---|---|---|---|
| Keep current scraping endpoint with governor only | Fast mitigation, no API key needed, reduces log storms and hammering | Unofficial, against general terms for automated scraping, current IP already blocked, may remain unavailable until block expires | Emergency fallback only |
| Official Windguru API + governor/cache | Documented limits, authenticated access, quota headers, cache TTLs, better long-term stability | Requires real API key/plan and response-shape adapter; public use must satisfy API terms | Best sustainable solution |
| Official widgets or explicit Windguru permission | Safest compliance path if service is primarily wind-station data | May not fit backend JSON API needs | Required if main product is effectively a wind-data service |

## Practical conclusion

For this project:

1. Implement governor/cache/coalescing/`Retry-After` handling immediately to stop abusive polling.
2. Add official Windguru API v1 support as the primary provider for production station data.
3. Keep the unofficial `iapi.php` endpoint disabled or behind a temporary fallback flag only.
4. Use environment variables for API keys and never commit them.
5. If WindSensor’s public product is primarily wind-station data, contact Windguru for permission before deploying official API usage publicly.

The core conclusion is:

```text
Best sustainable solution = official authenticated Windguru API v1 + governor/cache/Retry-After handling.
Governor alone = emergency mitigation only.
Scraping iapi.php should not remain the production strategy.
```

