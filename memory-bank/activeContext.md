# Active Context

## Current Work Focus
- **Windguru unit conversion** — OpenSpec change `convert-windguru-knots-to-ms` implemented, verified and **archived** to
  `openspec/changes/archive/2026-11-04-convert-windguru-knots-to-ms/`. Its two ADDED requirements were synced, creating the
  first main spec of this project, `openspec/specs/windguru-wind-provider/spec.md` (`openspec validate --all --strict`:
  1 passed, 0 failed). Implementation is still uncommitted.
- **Windguru wind provider** — archived as `2026-11-04-add-windguru-wind-provider`; its own spec delta was never synced and
  that is now intentional: the capability spec above restates that requirement with metres-per-second wording and supersedes
  it, so re-syncing the older archive would only reintroduce the obsolete "wind speed in knots" wording.
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
- [ ] Windguru rollout follow-ups (deferred, see design.md Open Questions): decide whether the fixed 24 h look-back
  becomes a `history-hours` config field; whether to drop `graph_info=1`; confirm server-side use of `iapi.php` is
  acceptable at expected request volume; update the external spots config so Dahab – Assalah uses
  `"provider": "windguru"`, `"id": "15693"`; decide later whether `/spots-data` should expose Windguru station metadata
- [ ] Commit the windguru conversion: provider + tests + archive move + the new main spec are all staged/unstaged work
  that is intentionally not committed yet
- [ ] Unit questions raised by the windguru conversion (design.md – Open Questions): should `/sensor-data` declare its
  unit explicitly, and must `windy` / `neduet` / `onechip` be audited or normalized so the API is not mixed-unit?
- [ ] Announce the breaking numeric change (~48.6 % smaller wind values for `windguru` sensors) to clients and to the owner
  of the external spots configuration before pointing more spots at this provider

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