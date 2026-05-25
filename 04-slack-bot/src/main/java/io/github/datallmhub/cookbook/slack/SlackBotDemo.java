package io.github.datallmhub.cookbook.slack;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;

/**
 * Recipe 04 — Slack bot (simulated workspace).
 *
 * <p>A planner-executor graph that responds to a Slack-style {@code @mention}:
 * <pre>
 *   plan  →  execute  →  post
 * </pre>
 *
 * <p>{@code plan} breaks the user request into 1-3 numbered steps. {@code execute}
 * carries them out against an in-memory "Slack" workspace (channels +
 * pinned messages). {@code post} formats the reply as it would appear in a
 * threaded message.
 *
 * <p>Why simulated? A real Slack app requires OAuth tokens, an HTTPS callback
 * URL, and a Slack workspace — too much friction for a cookbook recipe.
 * The {@link SlackWorkspace} below has the same surface as the calls you'd
 * make against {@code slack-api-client} in production, so swapping is mechanical.
 */
public class SlackBotDemo {

    static final StateKey<List<String>> PLAN    = StateKey.of("slack.plan",    (Class<List<String>>) (Class<?>) List.class);
    static final StateKey<List<String>> RESULTS = StateKey.of("slack.results", (Class<List<String>>) (Class<?>) List.class);

    public static void main(String[] args) {
        ChatClient chat = localOllamaClientOrNull();
        AgentGraph graph = buildGraph(chat, new SlackWorkspace());

        String mention = args.length > 0
                ? String.join(" ", args)
                : "@bot what's pinned in #engineering and how many people are in #general?";

        System.out.println("=== Slack bot (simulated) ===");
        System.out.println("[mode]      " + (chat != null ? "LIVE (Ollama)" : "STUB") + "\n");
        System.out.println("Mention:    " + mention + "\n");

        AgentResult result = graph.invoke(AgentContext.of(mention));

        System.out.println("\nBot reply (would be posted in-thread):");
        System.out.println("─".repeat(60));
        System.out.println(result.text());
        System.out.println("─".repeat(60));
    }

    public static AgentGraph buildGraph(@Nullable ChatClient chat, SlackWorkspace slack) {
        Agent plan = chat != null ? llmPlan(chat) : stubPlan();

        Agent execute = ctx -> {
            List<String> steps = ctx.get(PLAN);
            if (steps == null) steps = List.of();
            List<String> results = steps.stream()
                    .map(slack::execute)
                    .toList();
            results.forEach(r -> System.out.println("[execute]   " + r));
            return AgentResult.builder()
                    .stateUpdates(Map.of(RESULTS, results))
                    .completed(true)
                    .build();
        };

        Agent post = ctx -> {
            List<String> results = ctx.get(RESULTS);
            String body = results == null || results.isEmpty()
                    ? "I couldn't figure out what to do with that — could you rephrase?"
                    : results.stream().collect(Collectors.joining("\n"));
            return AgentResult.ofText(body);
        };

        return AgentGraph.builder()
                .name("slack-bot")
                .addNode("plan",    plan)
                .addNode("execute", execute)
                .addNode("post",    post)
                .addEdge("plan",    "execute")
                .addEdge("execute", "post")
                .build();
    }

    private static Agent llmPlan(ChatClient chat) {
        return ctx -> {
            String reply = chat.prompt()
                    .system("You are a Slack-bot planner. Output 1-3 numbered steps the bot will execute. "
                          + "Available actions: PIN_LIST(#channel), MEMBER_COUNT(#channel). "
                          + "Output strictly one action per line, no prose.")
                    .user(lastUser(ctx))
                    .call()
                    .content();
            List<String> steps = reply.lines()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
            System.out.println("[plan]      " + steps.size() + " step(s)");
            steps.forEach(s -> System.out.println("              · " + s));
            return AgentResult.builder()
                    .stateUpdates(Map.of(PLAN, steps))
                    .completed(true)
                    .build();
        };
    }

    private static Agent stubPlan() {
        return ctx -> {
            String text = lastUser(ctx).toLowerCase();
            List<String> steps = new java.util.ArrayList<>();
            if (text.contains("pinned")) steps.add("PIN_LIST(#engineering)");
            if (text.contains("people") || text.contains("count")) steps.add("MEMBER_COUNT(#general)");
            if (steps.isEmpty()) steps.add("MEMBER_COUNT(#general)");
            System.out.println("[plan]      " + steps.size() + " step(s)");
            return AgentResult.builder()
                    .stateUpdates(Map.of(PLAN, List.copyOf(steps)))
                    .completed(true)
                    .build();
        };
    }

    /**
     * In-memory simulation of a Slack workspace. Replace with {@code slack-api-client}
     * calls in production — the API surface is intentionally identical.
     */
    public static class SlackWorkspace {
        private final Map<String, Integer> memberCount = Map.of("#general", 247, "#engineering", 42);
        private final Map<String, List<String>> pinned = Map.of(
                "#engineering", List.of("📌 On-call rotation doc", "📌 Code-review checklist"),
                "#general",     List.of("📌 Welcome to the workspace!"));

        public String execute(String step) {
            if (step.startsWith("PIN_LIST(")) {
                String channel = between(step, "(", ")");
                List<String> pins = pinned.getOrDefault(channel, List.of());
                return pins.isEmpty()
                        ? "No pinned messages in " + channel
                        : "Pinned in " + channel + ":\n  " + String.join("\n  ", pins);
            }
            if (step.startsWith("MEMBER_COUNT(")) {
                String channel = between(step, "(", ")");
                Integer n = memberCount.get(channel);
                return n == null ? "Unknown channel " + channel : channel + " has " + n + " members";
            }
            return "Unsupported action: " + step;
        }

        private static String between(String s, String open, String close) {
            int i = s.indexOf(open), j = s.lastIndexOf(close);
            return (i < 0 || j < 0 || j <= i + 1) ? "" : s.substring(i + 1, j).trim();
        }
    }

    @Nullable
    private static ChatClient localOllamaClientOrNull() {
        String baseUrl = envOr("OLLAMA_HOST",  "http://localhost:11434");
        String model   = envOr("OLLAMA_MODEL", "llama3.2:3b");
        if (!isPortOpen(hostFromUrl(baseUrl), portFromUrl(baseUrl), 500)) return null;
        try {
            OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();
            OllamaChatModel chat = OllamaChatModel.builder()
                    .ollamaApi(api)
                    .defaultOptions(OllamaOptions.builder().model(model).temperature(0.1).build())
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

    private static String lastUser(AgentContext ctx) {
        return ctx.messages().get(ctx.messages().size() - 1).getText();
    }
}
