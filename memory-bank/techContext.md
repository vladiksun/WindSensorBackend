# Technology Context

## Technologies Used

### Core Framework
- **Micronaut 4.x** - Lightweight Java framework with compile-time DI, AOT optimization
- **Java 25** - Latest LTS with virtual threads as the default execution model
- **Netty** - Reactive HTTP server runtime

### Build & Tooling
- **Gradle** (Groovy DSL) - Build automation
- **Shadow Plugin** - Fat JAR creation
- **Jib Plugin** - Docker image building (eclipse-temurin:25-jre base)
- **Spotless Plugin** - Code formatting (Palantir Java Format)

### Dependencies
- **Micronaut Serde Jackson** - JSON serialization/deserialization
- **Micronaut RxJava3** - Reactive streams support
- **Micronaut Management** - Health checks, metrics endpoints
- **Micronaut Micrometer** - Metrics collection
- **Vavr 0.10.6** - Functional programming (Try, Option, Either)
- **Apache HttpClient5** - HTTP client for external API calls
- **Logback** - Logging framework

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
`src/main/resources/application.yml` - Main configuration with Micronaut settings and wind sensor provider URLs

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
  - vavr (functional)
  - httpclient5 (HTTP client)

runtime:
  - logback-classic (logging)
  - snakeyaml (YAML parsing)

test:
  - micronaut-http-client (testing)