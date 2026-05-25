package io.github.datallmhub.cookbook.triage;

import java.util.Map;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.Edge;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;

/**
 * Recipe 02 — Support ticket triage.
 *
 * <p>Classify an incoming customer ticket and route it to the matching
 * specialist agent via a {@code conditional} edge. A content-policy node
 * inspects the draft before it is sent — if the draft leaks something
 * sensitive (e.g. a password), the reply is escalated to a human.
 *
 * <pre>
 *   triage  →  (refund | billing | technical)  →  policy  →  reply
 * </pre>
 */
public class TriageDemo {

    enum Category { REFUND, BILLING, TECHNICAL }

    static final StateKey<Category> CATEGORY      = StateKey.of("ticket.category", Category.class);
    static final StateKey<String>   DRAFT         = StateKey.of("ticket.draft",    String.class);
    static final StateKey<Boolean>  POLICY_PASSED = StateKey.of("policy.passed",   Boolean.class);

    public static void main(String[] args) {
        ChatClient chat = localOllamaClientOrNull();
        AgentGraph graph = buildGraph(chat);

        String body = args.length > 0
                ? String.join(" ", args)
                : "I was charged twice for my October subscription. Please refund the duplicate.";

        System.out.println("=== Support ticket triage ===");
        System.out.println("[mode]      " + (chat != null ? "LIVE (Ollama)" : "STUB") + "\n");
        System.out.println("Ticket:     \"" + body + "\"\n");

        AgentResult result = graph.invoke(AgentContext.of(body));

        System.out.println("\nFinal reply:");
        System.out.println("─".repeat(60));
        System.out.println(result.text());
        System.out.println("─".repeat(60));
    }

    public static AgentGraph buildGraph(@Nullable ChatClient chat) {
        Agent triage    = chat != null ? llmTriage(chat) : stubTriage();
        Agent refund    = drafter("refund",    "Apologise for the duplicate charge and promise a refund within 5 business days.");
        Agent billing   = drafter("billing",   "Confirm the billing detail update and reassure the customer.");
        Agent technical = drafter("technical", "Suggest a troubleshooting step and promise escalation if it fails.");

        Agent policy = ctx -> {
            String draft = ctx.get(DRAFT);
            boolean ok = draft != null && !draft.toLowerCase().contains("password");
            System.out.println("[policy]    passed = " + ok);
            return AgentResult.builder()
                    .text(ok ? "ok" : "violation")
                    .stateUpdates(Map.of(POLICY_PASSED, ok))
                    .completed(true)
                    .build();
        };

        Agent reply = ctx -> {
            Boolean ok = ctx.get(POLICY_PASSED);
            String draft = ctx.get(DRAFT);
            String body = Boolean.TRUE.equals(ok)
                    ? "Hi,\n\n" + draft + "\n\n— Customer Support"
                    : "Hi,\n\nYour ticket has been escalated to a human agent.\n\n— Customer Support";
            return AgentResult.ofText(body);
        };

        return AgentGraph.builder()
                .name("support-triage")
                .addNode("triage",    triage)
                .addNode("refund",    refund)
                .addNode("billing",   billing)
                .addNode("technical", technical)
                .addNode("policy",    policy)
                .addNode("reply",     reply)
                .addEdge(Edge.conditional("triage", ctx -> ctx.get(CATEGORY) == Category.REFUND,    "refund"))
                .addEdge(Edge.conditional("triage", ctx -> ctx.get(CATEGORY) == Category.BILLING,   "billing"))
                .addEdge("triage",    "technical")
                .addEdge("refund",    "policy")
                .addEdge("billing",   "policy")
                .addEdge("technical", "policy")
                .addEdge("policy",    "reply")
                .build();
    }

    private static Agent llmTriage(ChatClient chat) {
        return ctx -> {
            String reply = chat.prompt()
                    .system("Classify the support ticket into exactly one of: REFUND, BILLING, TECHNICAL. "
                          + "Reply with only the label.")
                    .user(lastUser(ctx))
                    .call()
                    .content();
            Category cat = parseCategory(reply);
            System.out.println("[triage]    LLM said \"" + reply.trim() + "\" → " + cat);
            return AgentResult.builder()
                    .stateUpdates(Map.of(CATEGORY, cat))
                    .completed(true)
                    .build();
        };
    }

    private static Agent stubTriage() {
        return ctx -> {
            String t = lastUser(ctx).toLowerCase();
            Category cat = t.contains("refund") || t.contains("charged twice") ? Category.REFUND
                         : t.contains("invoice") || t.contains("bill")         ? Category.BILLING
                         : Category.TECHNICAL;
            System.out.println("[triage]    category = " + cat);
            return AgentResult.builder()
                    .stateUpdates(Map.of(CATEGORY, cat))
                    .completed(true)
                    .build();
        };
    }

    private static Agent drafter(String label, String hint) {
        return ctx -> {
            String draft = "[" + label + "] " + hint;
            System.out.println("[" + label + "]    drafted");
            return AgentResult.builder()
                    .text(draft)
                    .stateUpdates(Map.of(DRAFT, draft))
                    .completed(true)
                    .build();
        };
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
                    .defaultOptions(OllamaOptions.builder().model(model).temperature(0.2).build())
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

    private static Category parseCategory(String reply) {
        String upper = reply == null ? "" : reply.trim().toUpperCase();
        for (Category c : Category.values()) if (upper.contains(c.name())) return c;
        return Category.TECHNICAL;
    }
}
