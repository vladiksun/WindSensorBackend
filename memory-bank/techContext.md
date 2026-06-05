# Tech Context: WindSensorBackend

## Technologies Used
- **Language**: Java 21 (utilizing Virtual Threads)
- **Framework**: Micronaut
- **Build Tool**: Gradle
- **Libraries**:
  - **Vavr**: For functional programming constructs (specifically `Try` and `Option`).
  - **Apache HttpClient 5**: For making synchronous HTTP requests to upstream sensors.
  - **Jackson**: For JSON serialization and deserialization (via Micronaut's `ObjectMapper`).
  - **SLF4J**: For logging.

## Development Setup
- **IDE**: IntelliJ IDEA Community
- **Runtime**: JVM 21
- **Infrastructure**: 
  - Docker Compose is available in `dev_setup/` for local environment setup.
  - Ngrok is referenced in `dev_setup/ngrok.txt`, likely for exposing local endpoints for testing.

## Technical Constraints
- **Upstream Dependency**: The system is highly dependent on the availability and response time of external wind sensor APIs.
- **Statelessness**: The backend is stateless; configuration is fetched from remote sources rather than stored in a local database.

## Tool Usage Patterns
- **Virtual Threads**: Used in the controller layer to ensure that high-latency I/O operations don't exhaust the server's thread pool.
- **Monadic Flow**: Heavy use of `.flatMap()` and `.onFailure()` with Vavr's `Try` to handle the pipeline of HTTP request $\rightarrow$ response $\rightarrow$ parsing.