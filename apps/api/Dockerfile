FROM gradle:9.7.0-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle installDist --no-daemon
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache wget
COPY --from=build /src/build/install/openclub-api/ ./
EXPOSE 8080
CMD ["./bin/openclub-api"]
