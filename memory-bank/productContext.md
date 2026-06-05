# Product Context: WindSensorBackend

## Purpose
The WindSensorBackend serves as a middleware layer between wind sensor hardware/upstream APIs and client applications (likely for wingfoiling or similar wind-dependent sports). It abstracts the complexity of dealing with multiple different sensor providers, providing a consistent API for the frontend.

## Problems Solved
- **Provider Fragmentation**: Different wind sensors provide data in different formats. This service unifies them.
- **Configuration Management**: Centralizes the mapping of "spots" (locations) to sensors, allowing the frontend to query by spot rather than knowing sensor IDs and providers.
- **Performance**: Uses Virtual Threads to handle high-latency upstream HTTP requests without blocking the main server threads.

## How it Works
1. **Client Request**: A client requests data for a specific sensor or a list of spots.
2. **Proxy Logic**: 
   - For sensor data: The `ProxyService` identifies the correct `WindDataProvider` based on the provider code, fetches the raw data via HTTP, and lets the provider extract the relevant timed readings.
   - For spots data: The service fetches JSON configuration files from a remote source (GitHub in the current implementation).
3. **Response**: The data is returned as a standardized DTO (Data Transfer Object).

## User Experience Goals
- **Low Latency**: Fast responses despite depending on upstream APIs.
- **Reliability**: Graceful handling of upstream failures using `Try` monads from Vavr.
- **Extensibility**: Ability to add new sensor brands/providers with minimal changes to the core logic.