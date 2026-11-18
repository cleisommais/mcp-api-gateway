package com.prreview.apigateway.controller;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/** GET /health identifies the HTTP process; review dependency failures remain visible on jobs. */
public final class HealthController implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws java.io.IOException {
        HttpResponses.json(
                exchange,
                exchange.getRequestMethod().equals("GET") ? 200 : 405,
                java.util.Map.of("status", "UP", "component", "api-gateway"));
    }
}
