# Active Context

## Current Work Focus
Project is in active development with recent refactoring work completed. Latest commits focus on test infrastructure setup and OpenAPI documentation support.

## Recent Changes (as of 2026-06-06)
- **refactor tests** (728968e) - Removed `WindSensorBackendTest.java`, cleaned up test structure. Added testcontainers and MockServer dependencies to build.gradle for future test implementation.
- **add open api support** (5d3e37f) - Added `micronaut-openapi` dependency (both implementation and annotationProcessor). Configured Swagger UI views in application.yml at `/swagger/**` and `/swagger-ui/**`.
- **Multiple refactoring commits** - Ongoing code refactoring (c8ca8dc, fa8c4f7, ab64b23, cb4fca4, ed70872, etc.)
- **Global exception handler** - Moved to dedicated `GlobalExceptionHandler.java` bean

## Next Steps
- Implement actual tests using testcontainers + MockServer infrastructure (dependencies added but no test classes exist yet)
- Write unit tests for provider implementations (Windy, Neduet)
- Write integration tests for controller endpoints
- Consider adding caching layer for frequently requested sensor data
- Consider adding rate limiting for external API calls
- Explore additional wind data providers

## Active Decisions and Considerations
- Provider architecture uses a strategy pattern with `WindDataProvider<T>` interface
- Abstract base class `BaseWindyDataProvider<T>` shares common logic among Windy provider variants
- Each provider implements its own response parsing and data extraction
- Configuration is externalized via `application.yml` and a separate GitHub config repository
- Uses Vavr's `Try` and `Option` for functional error handling instead of exceptions
- OpenAPI/Swagger UI now enabled for API documentation
- Test infrastructure prepared (testcontainers, MockServer) but tests not yet written

## Important Patterns and Preferences
- Java records for DTOs (immutable, concise)
- Vavr library for functional programming constructs
- Apache HttpClient5 for HTTP communication
- Micronaut's dependency injection and configuration binding
- Virtual threads for I/O-bound handler execution (`@ExecuteOn(TaskExecutors.VIRTUAL)`)
- Palantir Java Format via Spotless plugin for code style

## Learnings and Project Insights
- The project is a stateless proxy/aggregator - no database involved
- All configuration (spots, sensors) lives in an external GitHub repo: `vladiksun/WindSensorConfig`
- Provider implementations are in `provider/impl/` package with multiple Windy variants
- Response models are organized by provider in `response/{provider}/` packages
- SSL and security are configurable but disabled by default for development
- Test Java directory is completely empty - test coverage is 0%

## Pending Tasks
- [ ] Write unit tests for provider implementations
- [ ] Write integration tests for controller endpoints using MockServer
- [ ] Document API contracts via OpenAPI annotations
- [ ] Review and potentially add caching strategy
- [ ] Consider rate limiting for external API calls