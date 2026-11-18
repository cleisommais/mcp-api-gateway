# The Java artifact is built locally; this image can run on OCI arm64 or amd64.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY dist/mcp-api-gateway.jar ./mcp-api-gateway.jar
COPY dist/lib ./lib
USER 10001:10001
