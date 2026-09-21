package io.github.datallmhub.cookbook.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * A minimal MCP server exposing an order system over stdio: {@code lookup_order}
 * and {@code refund_order}. The demo launches it as a child process, exactly as
 * it would launch any third-party MCP server.
 *
 * <p>Stdout carries the MCP protocol, so nothing else may print to it: logging
 * goes to stderr (see {@code logback.xml}).
 */
public class OrdersMcpServer {

    private static final Map<String, String> ORDERS = Map.of(
            "A-1001", "A-1001: 2x wireless headphones, 89.00 EUR, delivered 3 days ago",
            "A-1002", "A-1002: standing desk, 640.00 EUR, delivered yesterday");

    private static final String ORDER_ID_SCHEMA = """
            {"type":"object","properties":{"orderId":{"type":"string"}},"required":["orderId"]}""";

    private static final String REFUND_SCHEMA = """
            {"type":"object",
             "properties":{"orderId":{"type":"string"},"amount":{"type":"number"}},
             "required":["orderId","amount"]}""";

    public static void main(String[] args) throws InterruptedException {
        SyncToolSpecification lookup = new SyncToolSpecification(
                new McpSchema.Tool("lookup_order", "Look up an order by its id", ORDER_ID_SCHEMA),
                (exchange, arguments) -> text(ORDERS.getOrDefault(
                        String.valueOf(arguments.get("orderId")), "no such order")));

        SyncToolSpecification refund = new SyncToolSpecification(
                new McpSchema.Tool("refund_order", "Refund an order, fully or partially", REFUND_SCHEMA),
                (exchange, arguments) -> text("refunded " + arguments.get("amount")
                        + " EUR on order " + arguments.get("orderId")));

        McpServer.sync(new StdioServerTransportProvider())
                .serverInfo("orders-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(lookup, refund)
                .build();

        // The client ends the process when it closes the connection.
        new CountDownLatch(1).await();
    }

    private static McpSchema.CallToolResult text(String value) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(value)), false);
    }
}
