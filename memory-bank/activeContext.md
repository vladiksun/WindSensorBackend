# Active Context

## Current Work Focus
Initial project setup and memory bank initialization. The project is in an early stage with core functionality implemented.

## Recent Changes
- Memory bank has been initialized with all core documentation files
- Project structure analyzed and documented

## Next Steps
- Understand the existing provider implementations (Windy, Neduet)
- Identify any missing functionality or improvements needed
- Consider adding new wind data providers if required
- Explore testing coverage and add tests if needed

## Active Decisions and Considerations
- Provider architecture uses a strategy pattern with `WindDataProvider<T>` interface
- Each provider implements its own response parsing and data extraction
- Configuration is externalized via `application.yml` and a separate GitHub config repository
- Uses Vavr's `Try` and `Option` for functional error handling instead of exceptions

## Important Patterns and Preferences
- Java records for DTOs (immutable, concise)
- Vavr library for functional programming constructs
- Apache HttpClient5 for HTTP communication
- Micronaut's dependency injection and configuration binding
- Virtual threads for I/O-bound handler execution

## Learnings and Project Insights
- The project is a stateless proxy/aggregator - no database involved
- All configuration (spots, sensors) lives in an external GitHub repo: `vladiksun/WindSensorConfig`
- Provider implementations are in `provider/impl/` package
- Response models are organized by provider in `response/{provider}/` packages
- SSL and security are configurable but disabled by default for development

## Pending Tasks
- [ ] Review provider implementations in detail
- [ ] Understand response parsing for each provider
- [ ] Check if tests exist and their coverage
- [ ] Document any API contract details