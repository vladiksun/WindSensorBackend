# System Patterns

## Architecture Overview
The application follows a simple layered architecture with a provider plugin pattern:

```
Controller Layer (WindSensorController)
    ↓
Service Layer (ProxyService)
    ↓
Provider Layer (WindDataProvider implementations)
    ↓
External APIs (Windy, Neduet)
```

## Key Technical Decisions

### 1. Stateless Proxy Pattern
The application does not store any data. It acts as a real-time proxy that:
- Receives requests from clients
- Forwards to appropriate external providers
- Normalizes and returns responses

### 2. Provider Strategy Pattern
- `WindDataProvider<T>` interface defines the contract for all providers
- Each provider implements: URL generation, response parsing, data mapping
- Providers are discovered via Micronaut's DI and registered in a map by name
- New providers can be added by implementing the interface

### 3. Functional Error Handling with Vavr
- `Try<T>` for operations that can fail (replaces try-catch)
- `Option<T>` for nullable values (replaces null checks)
- Provides composable error handling without exception throwing

### 4. Java Records for DTOs
All data transfer objects are immutable Java records:
- `SensorDTO` - sensor identifier with provider info
- `SensorRequestDTO` - incoming request wrapper
- `SensorDataDTO` - normalized wind reading output
- `SpotDataDTO` - location/spot information

## Component Relationships

### WindSensorController
- Entry point for all HTTP requests
- Three endpoints: `/sensor-data` (POST), `/spots-data` (GET), `/spots-data-dahab` (GET)
- Uses `@ExecuteOn(TaskExecutors.VIRTUAL)` for virtual threads

### ProxyService
- Central orchestrator for all data fetching
- Maintains a map of `WindDataProvider` instances by provider code
- Handles HTTP communication with external providers via Apache HttpClient5
- Routes requests to correct provider based on sensor metadata

### WindDataProvider Interface
Generic interface parameterized by response type `T`:
- `getName()` - provider identifier
- `getUrl()` - base API URL
- `getCallUrl(sensorId)` - generates full API URL for a sensor
- `extractTimedReadings(...)` - parses raw response, returns normalized DTOs
- `mapToDTO(T)` - maps provider-specific measurement to SensorDataDTO
- `getLastReading(List<T>)` - extracts most recent reading

### WindSensorConfig
- Micronaut configuration bean bound from `application.yml`
- Holds provider URLs and spots data configuration

## Data Flow

### Sensor Data Request Flow:
1. Client POSTs to `/sensor-data` with `SensorRequestDTO`
2. Controller calls `ProxyService.requestTimedReadings()`
3. Service looks up provider by code from `SensorDTO`
4. Service makes HTTP GET to provider's API
5. Provider's `extractTimedReadings()` parses raw JSON response
6. Normalized `List<SensorDataDTO>` returned to client

### Spots Data Request Flow:
1. Client GETs `/spots-data` or `/spots-data-dahab`
2. Service fetches JSON from GitHub raw content URL
3. JSON is deserialized to `List<SpotDataDTO>` using Jackson
4. List returned to client

## Package Structure
```
com.vb.wingfoil
├── Application.java              # Micronaut entry point
├── WindSensorController.java     # REST endpoints
├── ProxyService.java             # Core service orchestrator
├── WindSensorConfig.java         # Configuration bean
├── ResponseHandlerContext.java   # Context for response processing
├── GlobalExceptionHandler.java   # Centralized error handling
├── SensorDTO.java                # Sensor identifier record
├── SensorRequestDTO.java         # Request wrapper record
├── SensorDataDTO.java            # Wind reading output record
├── SpotDataDTO.java              # Location data record
├── provider/
│   └── WindDataProvider.java     # Provider interface
│   └── impl/                     # Provider implementations
└── response/
    ├── windy/                    # Windy-specific response models
    └── neduet/                   # Neduet-specific response models