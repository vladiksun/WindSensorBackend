# Project Brief: WindSensorBackend

## Overview
A REST API backend service that aggregates wind speed and direction data from multiple wind sensor providers. Built for wind sports enthusiasts (wingfoil, kitesurfing) who need real-time wind condition data from various sensor locations.

## Core Requirements
- Fetch wind sensor data from external providers (Windy API, Neduet API)
- Fetch spot/location data from external configuration repository
- Provide a unified REST API for consuming wind data across providers
- Support configurable reading windows and number of readings per request
- Deployable as a Docker container
- Configurable via environment variables for production deployments

## Key Goals
- Abstract away provider-specific API differences behind a unified interface
- Enable easy addition of new wind data providers
- Keep configuration (spots, providers) externalized and maintainable
- Low-latency responses using Java virtual threads

## Scope
- Backend API only (no frontend)
- Two endpoints: sensor data retrieval and spots data retrieval
- Provider plugin architecture for extensibility
- External configuration stored in a separate GitHub repository (WindSensorConfig)

## Out of Scope
- Data storage/persistence (stateless proxy)
- User authentication (security is optional/configurable)
- Real-time push (WebSocket/SSE) - currently request/response only