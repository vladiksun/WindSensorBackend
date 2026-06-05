# Project Brief: WindSensorBackend

## Core Requirements
The goal of this project is to provide a REST API that acts as a proxy for fetching wind speed and direction data from various wind sensors.

## Objectives
- Provide a unified interface to access wind data from different sensor providers.
- Support fetching timed readings for specific sensors.
- Support fetching "spots" data (likely locations with sensors) from a configuration source.
- Implement a provider-based architecture to allow easy addition of new wind data sources.

## Key Features
- **Sensor Data Proxy**: Endpoint `/sensor-data` to get readings for a specific sensor.
- **Spots Data**: Endpoints `/spots-data` and `/spots-data-dahab` to retrieve location/spot information.
- **Dynamic Provider Resolution**: Use of `WindDataProvider` implementations to handle different upstream API formats.
- **Virtual Threads**: Utilization of Java Virtual Threads (`TaskExecutors.VIRTUAL`) for handling I/O-bound proxy requests.