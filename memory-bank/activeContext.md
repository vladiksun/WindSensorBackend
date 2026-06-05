# Active Context: WindSensorBackend

## Current Focus
The project is currently in the initialization phase of the memory bank. The core functionality (proxying wind sensor data and fetching spots configuration) is already implemented.

## Recent Changes
- Initialized the Memory Bank with `projectbrief.md`, `productContext.md`, and `activeContext.md`.

## Next Steps
- Complete the initialization of the remaining memory bank files: `systemPatterns.md`, `techContext.md`, and `progress.md`.
- Once the memory bank is initialized, the project is ready for further feature development or maintenance.

## Active Decisions and Considerations
- **Virtual Threads**: The use of `TaskExecutors.VIRTUAL` in the controller is a key architectural decision to optimize I/O bound operations.
- **Error Handling**: Using Vavr's `Try` monad to handle upstream API failures without throwing exceptions up the stack.
- **Configuration**: Spots data is currently fetched from raw GitHub files, which is a simple way to manage config without a database.