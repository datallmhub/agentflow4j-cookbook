package io.github.datallmhub.cookbook.mcp;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.ToolCallRecord;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.AgentListener;
import io.github.datallmhub.agentflow4j.graph.ToolPolicy;
import io.github.datallmhub.agentflow4j.squad.ExecutorAgent;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;

/**
 * Recipe 07: a governed MCP agent.
 *
 * <p>A support agent answers customer tickets with the tools of an order
 * system exposed over MCP. af4j does not reimplement MCP: Spring AI's
 * {@link SyncMcpToolCallbackProvider} turns the server's tools into
 * {@code ToolCallback}s, and handing that provider to
 * {@link ExecutorAgent.Builder#toolProviders} puts every MCP call under the
 * graph's governance:
 *
 * <ul>
 *   <li>a {@link ToolPolicy} refuses refunds above 100 EUR before the call
 *       reaches the MCP server; the model is told why and answers the
 *       customer;</li>
 *   <li>every call, allowed or refused, lands in the node's
 *       {@code AgentResult.toolCalls()} audit;</li>
 *   <li>an {@link AgentListener} sees each call through {@code onToolCall}.</li>
 * </ul>
 *
 * <p>Runs with no setup: the MCP server is a child Java process started by
 * the demo, and the model is a local Ollama when one is reachable, otherwise
 * a deterministic stub that drives the same tool calls.
 */
public class McpGovernedAgentDemo {

    private static final String CLIENT_NAME = "orders";
    private static final String REFUND_TOOL = McpToolUtils.prefixedToolName(CLIENT_NAME, "refund_order");
    private static final double REFUND_LIMIT_EUR = 100.0;
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"text\":\"(.*?)\"");

    private static final String SYSTEM_PROMPT = """
            You are a customer support agent for an online shop.
            Use lookup_order to check an order before acting on it.
            Use refund_order to refund. If a tool call is refused, explain the refusal to the customer.
            Answer in two sentences at most.""";

    public static void main(String[] args) {
        ChatClient ollama = localOllamaClientOrNull();
        ChatClient chat = ollama != null ? ollama : ChatClient.create(new ScriptedSupportModel());
        System.out.println("=== Recipe 07: Governed MCP agent ===");
        System.out.println("[mode] " + (ollama != null ? "LIVE (Ollama)" : "STUB") + "\n");

        McpSyncClient mcp = startOrdersServer();
        try {
            System.out.println("[mcp] server tools " + mcp.listTools().tools().stream().map(McpSchema.Tool::name).toList()
                    + ", exposed to the agent as " + CLIENT_NAME + "_*\n");

            ExecutorAgent support = ExecutorAgent.builder()
                    .name("support")
                    .chatClient(chat)
                    .systemPrompt(SYSTEM_PROMPT)
                    .toolProviders(new SyncMcpToolCallbackProvider(mcp))
                    .toolPolicy(ToolPolicy.when(
                            (tool, arguments) -> !REFUND_TOOL.equals(tool) || amount(arguments) <= REFUND_LIMIT_EUR,
                            "refunds above " + REFUND_LIMIT_EUR + " EUR need a human approval"))
                    .build();

            AgentGraph graph = AgentGraph.builder()
                    .name("support-desk")
                    .addNode("support", support)
                    .listener(new AgentListener() {
                        @Override
                        public void onToolCall(String graphName, String nodeName, ToolCallRecord call) {
                            System.out.printf("  [audit] %-22s %-7s %s%n", call.name(),
                                    call.success() ? "OK" : "REFUSED",
                                    call.success() ? plainText(call.result()) : call.error());
                        }
                    })
                    .build();

            for (String ticket : List.of(
                    "Where is my order A-1001?",
                    "Please refund 20 EUR on order A-1001, one headphone is scratched.",
                    "Refund order A-1002 in full, I changed my mind.")) {
                System.out.println("ticket: " + ticket);
                AgentResult result = graph.invoke(AgentContext.of(ticket));
                System.out.println("  reply: " + result.text() + "\n");
            }
        }
        finally {
            mcp.closeGracefully();
        }
    }

    // ── MCP wiring ────────────────────────────────────────────────────────────

    /** Starts {@link OrdersMcpServer} as a child process and connects to it over stdio. */
    private static McpSyncClient startOrdersServer() {
        String java = ProcessHandle.current().info().command().orElse("java");
        ServerParameters server = ServerParameters.builder(java)
                .args("-cp", classpath(), OrdersMcpServer.class.getName())
                .build();
        McpSyncClient client = McpClient.sync(new StdioClientTransport(server))
                .clientInfo(new McpSchema.Implementation(CLIENT_NAME, "1.0.0"))
                .requestTimeout(Duration.ofSeconds(20))
                .build();
        client.initialize();
        return client;
    }

    /**
     * The recipe's own classpath. Under {@code mvn exec:java} the
     * {@code java.class.path} property is Maven's, so it is read from the
     * class loader instead.
     */
    private static String classpath() {
        if (McpGovernedAgentDemo.class.getClassLoader() instanceof URLClassLoader loader) {
            List<String> entries = new ArrayList<>();
            for (URL url : loader.getURLs()) {
                try {
                    entries.add(Path.of(url.toURI()).toString());
                }
                catch (URISyntaxException | IllegalArgumentException ignored) {
                    // not a file URL, cannot be on a child JVM's classpath
                }
            }
            return String.join(File.pathSeparator, entries);
        }
        return System.getProperty("java.class.path");
    }

    /** MCP tool results arrive as a JSON list of content blocks; keep the text for display. */
    static String plainText(@Nullable String mcpResult) {
        if (mcpResult == null) {
            return "";
        }
        Matcher m = TEXT_BLOCK.matcher(mcpResult);
        List<String> texts = new ArrayList<>();
        while (m.find()) {
            texts.add(m.group(1));
        }
        return texts.isEmpty() ? mcpResult : String.join(" ", texts);
    }

    private static double amount(Map<String, Object> arguments) {
        return arguments.get("amount") instanceof Number n ? n.doubleValue() : Double.MAX_VALUE;
    }

    // ── Stub model ────────────────────────────────────────────────────────────

    /**
     * Stands in for an LLM when Ollama is not running: it picks the tool calls
     * a model would make for each ticket, runs them through Spring AI's
     * {@link ToolCallingManager} exactly as a real provider does, then words a
     * reply from the tool results, including a refusal.
     */
    static final class ScriptedSupportModel implements ChatModel {

        private static final Pattern ORDER_ID = Pattern.compile("A-\\d{4}");
        private static final Pattern AMOUNT = Pattern.compile("(\\d+(?:\\.\\d+)?) EUR");
        private static final Map<String, Double> ORDER_TOTALS = Map.of("A-1001", 89.0, "A-1002", 640.0);

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String ticket = lastUserText(prompt.getInstructions());
            String orderId = find(ORDER_ID, ticket);
            if (orderId == null) {
                return reply("Could you share your order id?");
            }
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            calls.add(toolCall("lookup_order", "{\"orderId\":\"" + orderId + "\"}"));
            if (ticket.toLowerCase().contains("refund")) {
                String requested = find(AMOUNT, ticket);
                double amount = requested != null ? Double.parseDouble(requested) : ORDER_TOTALS.getOrDefault(orderId, 0.0);
                calls.add(toolCall("refund_order", "{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + "}"));
            }
            ChatResponse toolRequest = new ChatResponse(List.of(new Generation(new AssistantMessage("", Map.of(), calls))));
            ToolExecutionResult execution = ToolCallingManager.builder().build().executeToolCalls(prompt, toolRequest);
            List<Message> history = execution.conversationHistory();
            ToolResponseMessage responses = (ToolResponseMessage) history.get(history.size() - 1);
            List<String> results = responses.getResponses().stream()
                    .map(r -> plainText(r.responseData()))
                    .toList();
            return reply(String.join(" / ", results));
        }

        private static AssistantMessage.ToolCall toolCall(String tool, String arguments) {
            String name = McpToolUtils.prefixedToolName(CLIENT_NAME, tool);
            return new AssistantMessage.ToolCall("call-" + tool, "function", name, arguments);
        }

        private static ChatResponse reply(String text) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }

        private static String lastUserText(List<Message> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof UserMessage user) {
                    return user.getText();
                }
            }
            return "";
        }

        @Nullable
        private static String find(Pattern pattern, String text) {
            Matcher m = pattern.matcher(text);
            return m.find() ? (m.groupCount() > 0 ? m.group(1) : m.group()) : null;
        }
    }

    // ── Ollama wiring (optional) ──────────────────────────────────────────────

    @Nullable
    private static ChatClient localOllamaClientOrNull() {
        String baseUrl = envOr("OLLAMA_HOST", "http://localhost:11434");
        String model = envOr("OLLAMA_MODEL", "llama3.2:3b");
        if (!isPortOpen(hostFromUrl(baseUrl), portFromUrl(baseUrl), 500)) return null;
        try {
            OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();
            OllamaChatModel chat = OllamaChatModel.builder()
                    .ollamaApi(api)
                    .defaultOptions(OllamaOptions.builder().model(model).temperature(0.0).build())
                    .build();
            return ChatClient.builder(chat).build();
        }
        catch (Throwable t) {
            return null;
        }
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String hostFromUrl(String url) {
        try { return java.net.URI.create(url).getHost(); }
        catch (Throwable t) { return "localhost"; }
    }

    private static int portFromUrl(String url) {
        try { int p = java.net.URI.create(url).getPort(); return p > 0 ? p : 11434; }
        catch (Throwable t) { return 11434; }
    }

    private static boolean isPortOpen(String host, int port, int timeoutMs) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        }
        catch (Throwable t) {
            return false;
        }
    }
}
