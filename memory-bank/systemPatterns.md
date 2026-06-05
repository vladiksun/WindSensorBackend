# System Patterns: WindSensorBackend

## Architecture
The system follows a layered architecture:
- **Controller Layer**: (`WindSenorController`) Handles incoming HTTP requests and defines the API surface.
- **Service Layer**: (`ProxyService`) Orchestrates the fetching of data from upstream sources and coordinates with providers.
- **Provider Layer**: (`WindDataProvider` interface) Encapsulates the logic for interacting with specific sensor brand APIs and parsing their unique response formats.
- **DTO Layer**: Standardized data objects (`SensorDTO`, `SensorDataDTO`, `SpotDataDTO`) for consistent data exchange.

## Key Technical Decisions
- **Provider Pattern**: Use of an interface (`WindDataProvider`) and a map of implementations in `ProxyService` allows the system to support multiple sensor types without modifying the core proxy logic (Open/Closed Principle).
- **Functional Error Handling**: Use of Vavr's `Try` monad instead of traditional try-catch blocks for upstream calls, allowing for a more declarative pipeline of transformations and failure handling.
- **Concurrency Model**: Utilization of Java Virtual Threads via Micronaut's `TaskExecutors.VIRTUAL` to handle the I/O-bound nature of proxying multiple HTTP requests efficiently.
- **Configuration as Code**: Using remote JSON files (hosted on GitHub) for "spots" configuration, simplifying updates without requiring a database or redeployments.

## Component Relationships
- `WindSenorController` $\rightarrow$ `ProxyService`
- `ProxyService` $\rightarrow$ `WindDataProvider` (via Interface)
- `ProxyService` $\rightarrow$ `WindSensorConfig` (for URLs and settings)
- `ProxyService` $\rightarrow$ `ObjectMapper` (for JSON parsing)