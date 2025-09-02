./gradlew buildLayers

./gradlew dockerfile

cd build\docker\main

docker build -t windsensorbackend:latest .