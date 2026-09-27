package io.github.datallmhub.cookbook.openhands;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in OpenHands server on a local port, so the recipe runs with no
 * account: it answers the handful of V1 endpoints the adapter calls and walks a
 * conversation from {@code running} to {@code finished}.
 *
 * <p>Set {@code OPENHANDS_API_KEY} to talk to the real OpenHands Cloud instead.
 */
final class LocalOpenHandsStub implements AutoCloseable {

    private final HttpServer server;
    private final Deque<String> statuses = new ArrayDeque<>();
    private final double costPerPoll;
    private double cost;

    LocalOpenHandsStub(double costPerPoll, String... scriptedStatuses) {
        this.costPerPoll = costPerPoll;
        this.statuses.addAll(List.of(scriptedStatuses));
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Could not start the local OpenHands stub", ex);
        }
        server.createContext("/api/v1/", this::handle);
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/v1/app-conversations") && exchange.getRequestMethod().equals("POST")) {
            // The sandbox is ready straight away; the real API often needs a few polls.
            respond(exchange, """
                    {"id":"task-1","status":"READY","app_conversation_id":"conv-1","sandbox_id":"sb-1"}""");
            return;
        }
        if (path.equals("/api/v1/app-conversations")) {
            String status = statuses.isEmpty() ? "finished" : statuses.poll();
            cost += costPerPoll;
            respond(exchange, """
                    [{"id":"conv-1","sandbox_id":"sb-1","sandbox_status":"RUNNING","execution_status":"%s",
                      "selected_repository":"acme/billing","selected_branch":"openhands/fix-1042",
                      "pr_number":1042,"last_agent_message":"Fixed the rounding bug and opened a pull request.",
                      "metrics":{"accumulated_cost":%s,
                                 "accumulated_token_usage":{"prompt_tokens":8200,"completion_tokens":1450}}}]"""
                    .formatted(status, cost));
            return;
        }
        respond(exchange, "{}");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
