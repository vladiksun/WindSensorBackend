# Product Context

## Why This Project Exists
Wind data is scattered across multiple providers (Windy, Neduet, etc.), each with different APIs, data formats, and authentication methods. Wind sports enthusiasts and application developers need a single, unified endpoint to query wind sensor data without integrating with each provider individually.

## Problems It Solves
1. **Provider Fragmentation**: Each wind data provider has a unique API format and access pattern
2. **Integration Complexity**: Frontend applications must handle multiple API protocols
3. **Provider Failover**: No easy way to switch providers when one is unavailable
4. **Location Discovery**: Difficult to find available sensor locations across providers
5. **Data Normalization**: Wind data formats vary significantly between providers

## How It Should Work
- Client sends a request with sensor ID and/or location parameters
- Backend routes the request to the appropriate provider(s)
- Data is normalized into a consistent format (Burst/Mean speed in knots)
- Response is returned to the client with unified structure
- Debug mode available for development and troubleshooting

## User Experience Goals
- **Simple API**: Clean REST endpoints with intuitive request/response format
- **Fast Responses**: Leverage Micronaut's fast startup and virtual threads for quick I/O
- **Reliable**: Graceful error handling when providers are unavailable
- **Discoverable**: OpenAPI/Swagger UI for API exploration
- **Transparent**: Debug mode shows raw provider responses for troubleshooting

## Domain Context
- **Wind Sports**: Wingfoil, kitesurfing, windsurfing athletes need accurate wind data
- **Sensor Networks**: Professional wind sensors deployed at popular spots
- **Key Metrics**: Burst speed (gusts), Mean speed (average), measured in knots
- **Geography**: Focus on Mediterranean/Egypt locations (Dahab specifically)

## Value Proposition
- Single integration point for multiple wind data sources
- Normalized data format reduces frontend complexity
- Easy provider addition without client code changes
- Debug capabilities for data quality verification