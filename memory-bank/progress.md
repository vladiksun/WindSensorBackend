# Progress

## What Works
- Core Micronaut application boots and runs on port 8080
- REST API endpoints functional:
  - `POST /sensor-data` - Fetches wind readings from providers
  - `GET /spots-data` - Fetches available sensor locations (with optional `isDebug` flag)
  - `GET /spots-data-dahab` - Fetches Dahab-specific locations (with optional `isDebug` flag)
- Provider plugin system with Windy and Neduet implementations
- Multiple Windy provider variants via `BaseWindyDataProvider` abstract base class
- Configuration binding from `application.yml`
- External spots configuration loaded from GitHub
- Docker image building via Jib plugin
- Code formatting via Spotless plugin (Palantir Java Format)
- Metrics and health check endpoints enabled
- Virtual threads for I/O-bound request handling
- OpenAPI/Swagger UI enabled at `/swagger/**` and `/swagger-ui/**`
- Global exception handling via dedicated `GlobalExceptionHandler` bean
- Test infrastructure dependencies configured (testcontainers, MockServer)

## What's Left to Build
- [ ] Test implementation (testcontainers + MockServer dependencies added, but no test classes exist)
- [ ] Additional wind data providers as needed
- [ ] Caching layer for frequently requested data
- [ ] Rate limiting for external API calls
- [ ] Comprehensive error response format
- [ ] OpenAPI annotations on controller endpoints
- [ ] CI/CD pipeline configuration

## Current Status
- **Phase**: Active development / MVP+
- **Version**: 0.1
- **Core functionality**: Implemented and stable
- **Testing**: Infrastructure prepared, tests not written (0% coverage)
- **Documentation**: Memory bank maintained, OpenAPI support added
- **Recent activity**: Test refactoring, OpenAPI integration, ongoing code refactoring

## Known Issues
- Test Java directory is completely empty (`src/test/java/com/vb/wingfoil/` has no files)
- `WindSensorBackendTest.java` was removed in latest refactor
- SSL configuration requires keystore for production (`SSL_ENABLED:true` by default)
- Security module is commented out but configurable via `SECURITY_ENABLED`

## Evolution of Project Decisions
- Started with Micronaut 4.x for AOT and fast startup
- Chose Java 25 for virtual threads support
- Selected Vavr for functional error handling over traditional exceptions
- Externalized spots configuration to separate GitHub repo for easy maintenance
- Stateless design chosen to simplify deployment and scaling
- Added OpenAPI support for API documentation (2026-06-05)
- Added testcontainers + MockServer for test infrastructure (2026-06-06)
- Moved exception handling to dedicated `@Controller` bean for cleaner separation