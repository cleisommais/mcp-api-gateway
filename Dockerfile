# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress verify

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
COPY --from=build /workspace/target/mcp-api-gateway.jar ./mcp-api-gateway.jar
COPY --from=build /workspace/target/lib ./lib
USER 10001:10001
ENTRYPOINT ["java", "-Dorg.slf4j.simpleLogger.defaultLogLevel=off", "-cp", "/app/mcp-api-gateway.jar:/app/lib/*", "dev.cleisommais.mcp.f1.FixtureClient"]
