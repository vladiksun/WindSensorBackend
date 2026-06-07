# Project Brief - WindSensorBackend

## Overview
WindSensorBackend is a REST API service that aggregates wind sensor data from multiple external providers (Windy, Neduet) and provides a unified interface for fetching wind readings and sensor location information.

## Core Requirements
- Aggregate wind data from multiple external API providers
- Provide a unified REST API for wind sensor queries
- Support sensor location discovery (spots data)
- Plugin-based architecture for easy provider addition
- Stateless, horizontally scalable design
- Fast startup and low memory footprint via Micronaut AOT

## Key Goals
- Single API endpoint for wind data regardless of source provider
- Easy integration of new wind data providers
- Reliable external API communication with proper error handling
- Docker-native deployment
- Production-ready with SSL, metrics, and health checks

## Scope
- Wind data fetching (Burst and Mean wind speeds in knots)
- Sensor location management (spots data from external config)
- Multi-provider support (Windy variants, Neduet)
- Debug mode for development and troubleshooting
- API documentation via OpenAPI/Swagger UI

## Out of Scope
- Data persistence (no database)
- User authentication/authorization (security commented out)
- Real-time push notifications (polling-based only)
- Historical data storage
- Caching layer

## Target Users
- Wind sports enthusiasts (wingfoil, kitesurfing)
- Mobile/web applications needing wind data
- Dashboard applications for wind visualization

## Current Status
- Framework: Micronaut 5.0.0, Java 25
- Core functionality implemented and stable
- Test infrastructure prepared, tests pending
- Deployed via Docker containers