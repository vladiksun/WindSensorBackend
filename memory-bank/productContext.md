# Product Context

## Why This Project Exists
Wind sports enthusiasts (wingfoil, kitesurfing) need access to real-time wind data from various sensor stations to plan their sessions. Wind data is scattered across multiple providers with different API formats, making it difficult to get a unified view of conditions.

## Problems It Solves
1. **Fragmented Data Sources**: Wind sensor data is available from multiple providers (Windy, Neduet, etc.), each with their own API format and authentication
2. **Inconsistent API Contracts**: Each provider returns data in a different structure, requiring custom parsing logic
3. **Location Discovery**: Users need to know which sensor stations exist and where they are located
4. **Configuration Management**: Sensor locations and metadata need to be maintained separately from the application code

## How It Should Work
1. A client (mobile app, web frontend) sends a request with a sensor identifier
2. The backend identifies the correct provider based on the sensor's provider code
3. The backend calls the provider's API, parses the response, and returns normalized wind data
4. Clients can also fetch a list of available spots/locations with their associated sensors

## User Experience Goals
- Fast responses (virtual threads for I/O-bound operations)
- Consistent data format regardless of the underlying provider
- Easy to add new providers without changing the API contract
- External configuration allows updating sensor locations without redeploying

## Target Users
- Wind sports app developers who need a unified wind data API
- Personal use for checking wind conditions at specific locations
- Primarily focused on Russia (Neduet provider) and international locations (Windy provider)