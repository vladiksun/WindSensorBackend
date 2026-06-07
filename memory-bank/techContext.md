# Technology Context

## Technologies Used

### Core Framework
- **Micronaut 5.x** - Lightweight Java framework with compile-time DI, AOT optimization (migrated from 4.x to 5.0.0)
- **Java 25** - Latest LTS with virtual threads as the default execution model
- **Netty** - Reactive HTTP server runtime

### Build & Tooling
- **Gradle** (Groovy DSL) - Build automation
- **Shadow Plugin 9.4.1** - Fat JAR creation
- **Jib Plugin 3.4.5** - Docker image building (eclipse-temurin:25-jre base)
- **Spotless Plugin 8.5.1** - Code formatting (Palantir Java Format)
- **Micronaut Application Plugin 5.0.0** - Micronaut 5 application support
- **Micronaut AOT Plugin 5.0.0** - AOT optimization support

### Dependencies
- **Micronaut Serde Jackson** - JSON serialization/deserialization
- **Micronaut RxJava3** - Reactive streams support
- **Micronaut Management** - Health checks, metrics endpoints
- **Micronaut Micrometer** - Metrics collection
- **Micronaut OpenAPI** - API documentation, Swagger UI generation (added 2026-06-05)
- **Micronaut HTTP Validation** - Request validation via annotation processor
- **Vavr 0.10.6** - Functional programming (Try, Option, Either)
- **Apache HttpClient5 5.5** - HTTP client for external API calls
- **Logback** - Logging framework

### Test Dependencies (Added but Not Used)
- **Micronaut HTTP Client** - HTTP testing support
- **Micronaut Test RestAssured** - Integration testing framework
- **Testcontainers MockServer** - Container-based API mocking
- **MockServer Client Java 5.15.0** - Client library for MockServer (downgraded from 6.1.0 for compatibility)
- **JUnit Platform Launcher** - Test runtime support for JUnit Platform discovery and execution

### Optional/Commented
- **Micronaut Security** - Present in dependencies but commented out, configurable via `SECURITY_ENABLED`

## Development Setup

### Prerequisites
- Java 25 JDK
- Docker (for containerized development via `dev_setup/docker-compose.yml`)
- Gradle wrapper included (`./gradlew`)

### Running Locally
```bash
./gradlew run
```

### Building Docker Image
```bash
./gradlew jibDockerBuild
```
Image tagged as `windsensorbackend:latest`

### Dev Scripts
- `dev_setup/docker.sh` - Docker management script
- `dev_setup/docker-compose.yml` - Compose configuration
- `dev_setup/ngrok.txt` - Ngrok tunneling notes

## Configuration

### Environment Variables
| Variable | Default | Description |
|----------|---------|-------------|
| `SSL_ENABLED` | `true` | Enable SSL/TLS |
| `KEY_STORE_PATH` | `classpath:keystore.p12` | Keystore location |
| `KEY_STORE_PASSWORD` | `changeit` | Keystore password |
| `KEY_STORE_TYPE` | `PKCS12` | Keystore type |
| `SECURITY_ENABLED` | `false` | Enable Micronaut security |
| `SPOTS_DATA_URL` | GitHub raw URL | Spots configuration URL |
| `SPOTS_DATA_MEDIA_TYPE` | `text/plain` | Media type for spots data |

### Configuration File
`src/main/resources/application.yml` - Main configuration with Micronaut settings, OpenAPI views, and wind sensor provider URLs

### Test Configuration
`src/test/resources/application-test.yml` - Minimal test config (port 8080, SSL disabled)

## Technical Constraints
- Java 25 required (virtual threads, pattern matching features)
- Stateless design - no database or cache layer
- External dependencies: Windy API, Neduet API, GitHub raw content for config
- AOT optimizations configured but some disabled (service loading, YAML conversion)

## Dependencies Summary
```
compile:
  - micronaut-serde-jackson (JSON)
  - micronaut-rxjava3 (reactive)
  - micronaut-management (ops)
  - micronaut-micrometer-core (metrics)
  - micronaut-openapi (API docs/Swagger UI)
  - vavr (functional)
  - httpclient5 (HTTP client)

runtime:
  - logback-classic (logging)
  - snakeyaml (YAML parsing)

test:
  - micronaut-http-client (testing)
  - micronaut-test-rest-assured (testing)
  - testcontainers:mockserver (container testing)
  - mockserver-client-java:5.15.0 (mock client)
  - junit-platform-launcher (test runtime)
