# Active Context

## Current Focus
Completed integration testing for `WindSensorController` using Testcontainers MockServer Module, REST Assured, and Micronaut Test framework.

## Recent Changes (2026-06-07)
- Created `WindSensorControllerIntegrationTest` with 6 integration tests covering all controller endpoints
- Created `application-test.yml` for test environment configuration
- Updated `build.gradle` with test dependencies:
  - `test-implementation`: `io.micronaut.test:micronaut-test-rest-assured`, `io.micronaut.test:micronaut-test-junit5`
  - `testImplementation`: `org.testcontainers:mockserver`, `org.testcontainers:junit-jupiter`, `org.mock-server:mockserver-client-java`
- Forced `com.networknt:json-schema-validator` to version `1.0.89` to fix test dependency conflicts

## Test Coverage
All endpoints in `WindSensorController` are covered:
1. `POST /sensor-data` - Neduet provider with timed readings
2. `POST /sensor-data` - Empty response handling
3. `GET /spots-data` - Normal mode
4. `GET /spots-data` - Empty response
5. `GET /spots-data?isDebug=true` - Debug mode (uses test data URL)
6. `GET /spots-data-dahab` - Dahab-specific endpoint

## Key Patterns
- Service layer is NOT mocked - tests exercise full application logic
- External HTTP dependencies are mocked via MockServer container
- Test properties injected via `TestPropertyProvider.getProperties()`
- MockServer container shared across all tests via `@Container` and `PER_CLASS` lifecycle
- `@BeforeEach` resets MockServer expectations between tests

## Next Steps
- Consider adding tests for Windy provider
- Consider adding negative tests (HTTP error responses from upstream services)