# Build Java without running test doubles; acceptance uses the deployed services and real providers.
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress -DskipTests package

FROM scratch AS artifact
COPY --from=build /workspace/target/mcp-api-gateway.jar /mcp-api-gateway.jar
COPY --from=build /workspace/target/lib /lib
