package com.prreview.apigateway.controller;

import com.prreview.common.json.Json;
import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;

final class HttpResponses {
    private HttpResponses() {}

    static void json(HttpExchange exchange, int status, Object result) throws java.io.IOException {
        byte[] body = Json.write(result).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
