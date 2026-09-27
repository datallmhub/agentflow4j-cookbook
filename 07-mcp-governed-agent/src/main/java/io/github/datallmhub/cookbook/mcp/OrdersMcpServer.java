package io.github.datallmhub.cookbook.mcp;

import java.util.Map;
import java.util.concurrent.CountDownLatch;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
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
        // Resolved through the SDK's service loader: mcp-json-jackson2 provides it.
        McpJsonMapper json = McpJsonDefaults.getMapper();

        SyncToolSpecification lookup = SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("lookup_order")
                        .description("Look up an order by its id")
                        .inputSchema(json, ORDER_ID_SCHEMA)
                        .build())
                .callHandler((exchange, request) -> text(ORDERS.getOrDefault(
                        String.valueOf(request.arguments().get("orderId")), "no such order")))
                .build();

        SyncToolSpecification refund = SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("refund_order")
                        .description("Refund an order, fully or partially")
                        .inputSchema(json, REFUND_SCHEMA)
                        .build())
                .callHandler((exchange, request) -> text("refunded " + request.arguments().get("amount")
                        + " EUR on order " + request.arguments().get("orderId")))
                .build();

        McpServer.sync(new StdioServerTransportProvider(json))
                .serverInfo("orders-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(lookup, refund)
                .build();

        // The client ends the process when it closes the connection.
        new CountDownLatch(1).await();
    }

    private static McpSchema.CallToolResult text(String value) {
        return McpSchema.CallToolResult.builder().addTextContent(value).isError(false).build();
    }
}
