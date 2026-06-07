# Active Context

## Current Work Focus
- **Test Infrastructure Preparation** - Finalizing test dependencies for JUnit Platform compatibility
- **Micronaut 5 Migration** - Completed migration from Micronaut 4.x to Micronaut 5.0.0 (commit `56bba3f`)
  - Updated `io.micronaut.application` plugin to version 5.0.0
  - Updated `io.micronaut.aot` plugin to version 5.0.0
  - Verified application compatibility with Micronaut 5 runtime

## Recent Changes
1. **Add junit-platform-launcher** (2026-06-07, commit `90cb6ef`) - Added `testRuntimeOnly` dependency for JUnit Platform test execution support
2. **Refactoring** (commit `531a836`) - Code cleanup and structure improvements
3. **Update memory bank** (commit `24f1af6`) - Previous documentation update
4. **Migrate to Micronaut 5** (commit `56bba3f`) - Major framework upgrade
5. **Refactor tests** (commit `728968e`) - Test infrastructure preparation, testcontainers + MockServer dependencies added
6. **Add OpenAPI support** (commit `5d3e37f`) - Swagger UI documentation enabled

## Next Steps
- [ ] Write actual test classes (test infrastructure is ready but `src/test/java` is empty)
- [ ] Add OpenAPI annotations to controller endpoints for proper API documentation
- [ ] Implement caching layer for frequently requested data
- [ ] Add rate limiting for external API calls
- [ ] Create comprehensive error response format
- [ ] Additional wind data providers as needed

## Active Decisions and Considerations
- Micronaut 5 chosen for latest framework improvements and long-term support
- Stateless architecture maintained - no database or cache layer
- Virtual threads (Java 25) used for I/O-bound request handling
- Vavr used for functional error handling patterns
- External configuration loaded from GitHub raw content
- JUnit Platform Launcher added for proper test discovery and execution

## Important Patterns and Preferences
- Plugin-based provider system (`WindDataProvider` interface with implementations)
- Abstract base classes for similar providers (`BaseWindyDataProvider`)
- Global exception handling via dedicated `@Controller` bean (`GlobalExceptionHandler`)
- Configuration externalized via `application.yml` with environment variable overrides

## Learnings and Project Insights
- Micronaut 5 migration was straightforward due to clean architecture
- Test infrastructure (testcontainers, MockServer, JUnit Platform Launcher) fully prepared but tests not yet written
- OpenAPI integration working but endpoint annotations still needed
- MockServer client downgraded from 6.1.0 to 5.15.0 for compatibility
- JUnit Platform Launcher required as testRuntimeOnly for proper test execution in modern Gradle setups