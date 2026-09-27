package io.github.datallmhub.cookbook.selfcorrect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.AgentRunEvent;
import io.github.datallmhub.agentflow4j.graph.Edge;
import io.github.datallmhub.agentflow4j.graph.InMemoryRunLogStore;
import io.github.datallmhub.agentflow4j.graph.RunEventType;
import io.github.datallmhub.agentflow4j.graph.RunOptions;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;

/**
 * Recipe 08: a self-correcting writer.
 *
 * <p>A writer agent drafts a product description; a deterministic reviewer
 * checks it against house rules and, when it fails, sends it back with the
 * list of issues. The loop is plain graph structure, so it is:
 *
 * <ul>
 *   <li><b>bounded</b>: after {@value #MAX_ATTEMPTS} drafts the reviewer
 *       escalates to a human instead of looping, and {@code maxIterations}
 *       is a hard backstop;</li>
 *   <li><b>explicit</b>: the reviewer is code, not a second LLM call, so the
 *       acceptance criteria are testable and free;</li>
 *   <li><b>audited</b>: every attempt shows up in the run log.</li>
 * </ul>
 *
 * <p>Runs with no setup: the writer uses a local Ollama model when one is
 * reachable, otherwise a deterministic stub that gets the first draft wrong.
 */
public class SelfCorrectingWriterDemo {

    static final StateKey<String> BRIEF = StateKey.of("brief", String.class);
    static final StateKey<String> DRAFT = StateKey.of("draft", String.class);
    static final StateKey<String> ISSUES = StateKey.of("review.issues", String.class);
    static final StateKey<Integer> ATTEMPTS = StateKey.of("draft.attempts", Integer.class);

    static final int MAX_ATTEMPTS = 3;
    static final int MAX_TITLE_LENGTH = 60;
    static final List<String> BANNED_CLAIMS = List.of("guaranteed", "100%", "best in the world", "miracle");

    public static void main(String[] args) {
        ChatClient chat = localOllamaClientOrNull();
        System.out.println("=== Recipe 08: Self-correcting writer ===");
        System.out.println("[mode] " + (chat != null ? "LIVE (Ollama)" : "STUB") + "\n");

        InMemoryRunLogStore runLog = new InMemoryRunLogStore();
        AgentGraph graph = AgentGraph.builder()
                .name("product-copy")
                .addNode("write", writer(chat))
                .addNode("review", SelfCorrectingWriterDemo::review)
                .addNode("publish", ctx -> AgentResult.ofText("PUBLISHED\n" + ctx.get(DRAFT)))
                .addNode("escalate", ctx -> AgentResult.ofText(
                        "NEEDS A HUMAN EDITOR after " + ctx.get(ATTEMPTS) + " drafts. Open issues: " + ctx.get(ISSUES)))
                .addEdge("write", "review")
                .addEdge(Edge.conditional("review", ctx -> ctx.get(ISSUES).isEmpty(), "publish"))
                .addEdge(Edge.conditional("review", ctx -> ctx.get(ATTEMPTS) >= MAX_ATTEMPTS, "escalate"))
                .addEdge("review", "write")
                // backstop: MAX_ATTEMPTS x (write + review) + a final node
                .maxIterations(2 * MAX_ATTEMPTS + 1)
                .runLog(runLog)
                .build();

        run(graph, "brief-1", "Noise-cancelling wireless headphones, 30h battery, foldable, for commuters.");
        run(graph, "brief-2", "A miracle anti-ageing cream that is guaranteed to erase wrinkles.");
    }

    private static void run(AgentGraph graph, String runId, String brief) {
        System.out.println("── " + runId + ": " + brief);
        AgentResult result = graph.invoke(AgentContext.of(brief).with(BRIEF, brief), RunOptions.ofRunId(runId));
        System.out.println(result.text());
        String path = graph.runLog(runId).stream()
                .filter(event -> event.type() == RunEventType.NODE_EXIT)
                .map(AgentRunEvent::node)
                .collect(java.util.stream.Collectors.joining(" -> "));
        System.out.println("  run log: " + path + "\n");
    }

    // ── Writer ────────────────────────────────────────────────────────────────

    private static Agent writer(@Nullable ChatClient chat) {
        return ctx -> {
            int attempt = ctx.get(ATTEMPTS) == null ? 1 : ctx.get(ATTEMPTS) + 1;
            String previous = ctx.get(DRAFT);
            String issues = ctx.get(ISSUES);
            String prompt = previous == null
                    ? "Write product copy for: " + ctx.get(BRIEF)
                    : "Revise this product copy.\n\n" + previous + "\n\nFix these issues: " + issues;
            String draft = chat != null
                    ? chat.prompt().system(HOUSE_STYLE).user(prompt).call().content()
                    : stubDraft(ctx.get(BRIEF), attempt);
            System.out.printf("  [write  #%d] %s%n", attempt, firstLine(draft));
            return AgentResult.builder()
                    .stateUpdates(java.util.Map.of(DRAFT, draft.strip(), ATTEMPTS, attempt))
                    .completed(true)
                    .build();
        };
    }

    private static final String HOUSE_STYLE = """
            You write e-commerce product copy. Output exactly this format and nothing else:
            Title: <at most 60 characters>
            - <benefit>
            - <benefit>
            - <benefit>
            Never promise results: no "guaranteed", "100%", "best in the world" or "miracle".""";

    /** Gets the first draft wrong on purpose, then applies the reviewer's feedback, unless the brief itself is the problem. */
    private static String stubDraft(String brief, int attempt) {
        if (brief.contains("miracle")) {
            return """
                    Title: The miracle cream
                    - Guaranteed to erase wrinkles
                    - Visible results overnight
                    - Loved by dermatologists""";
        }
        if (attempt == 1) {
            return """
                    Title: The best in the world noise-cancelling wireless headphones for every commuter
                    - 30 hours of battery, guaranteed
                    - Folds flat into any bag""";
        }
        return """
                Title: Quiet commute headphones, 30h battery
                - Active noise cancelling for trains and buses
                - 30 hours of playback on a single charge
                - Folds flat into any bag""";
    }

    // ── Reviewer ──────────────────────────────────────────────────────────────

    /** Deterministic acceptance criteria: testable, explainable, and no extra LLM call. */
    static AgentResult review(AgentContext ctx) {
        String draft = ctx.get(DRAFT);
        List<String> issues = new ArrayList<>();
        List<String> lines = draft.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();

        String title = lines.isEmpty() || !lines.get(0).startsWith("Title:") ? null : lines.get(0).substring(6).strip();
        if (title == null) {
            issues.add("the first line must be 'Title: ...'");
        }
        else if (title.length() > MAX_TITLE_LENGTH) {
            issues.add("the title has " + title.length() + " characters, the limit is " + MAX_TITLE_LENGTH);
        }
        long bullets = lines.stream().filter(l -> l.startsWith("- ")).count();
        if (bullets != 3) {
            issues.add("there must be exactly 3 bullet points, found " + bullets);
        }
        String lower = draft.toLowerCase(Locale.ROOT);
        for (String claim : BANNED_CLAIMS) {
            if (lower.contains(claim)) {
                issues.add("remove the claim '" + claim + "'");
            }
        }

        String verdict = issues.isEmpty() ? "accepted" : String.join("; ", issues);
        System.out.println("  [review  ] " + verdict);
        return AgentResult.builder()
                .stateUpdates(java.util.Map.of(ISSUES, issues.isEmpty() ? "" : verdict))
                .completed(true)
                .build();
    }

    private static String firstLine(String text) {
        return text.strip().lines().findFirst().orElse("");
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
}
