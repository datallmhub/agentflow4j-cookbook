package io.github.datallmhub.cookbook.parallel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.AgentRunEvent;
import io.github.datallmhub.agentflow4j.graph.ApprovalGate;
import io.github.datallmhub.agentflow4j.graph.BudgetLimits;
import io.github.datallmhub.agentflow4j.graph.BudgetPolicy;
import io.github.datallmhub.agentflow4j.graph.InMemoryCheckpointStore;
import io.github.datallmhub.agentflow4j.graph.InMemoryRunLogStore;
import io.github.datallmhub.agentflow4j.graph.ResumeOptions;
import io.github.datallmhub.agentflow4j.graph.RunEventType;
import io.github.datallmhub.agentflow4j.graph.RunOptions;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;

/**
 * Recipe 10: a parallel research squad.
 *
 * <p>Three researchers work on the same brief at the same time, and a writer
 * joins their findings once all three are in. The fan-out is plain graph
 * structure: three direct edges out of {@code plan}, and three direct edges
 * into {@code report}.
 *
 * <p>The point of the recipe is what the runtime keeps doing while branches run
 * concurrently:
 *
 * <ul>
 *   <li>one shared {@code BudgetPolicy} caps the whole fan-out, not each
 *       branch;</li>
 *   <li>an {@code ApprovalGate} on the paid source pauses <b>only that
 *       branch</b>: the two free researchers finish and their findings are
 *       checkpointed, then {@code resume} runs the approved branch without
 *       replaying them;</li>
 *   <li>the run log shows what ran in parallel and what was skipped.</li>
 * </ul>
 *
 * <p>Runs with no setup: researchers use a local Ollama model when one is
 * reachable, otherwise a deterministic stub.
 */
public class ParallelResearchDemo {

    static final StateKey<String> BRIEF = StateKey.of("brief", String.class);
    static final StateKey<String> MARKET = StateKey.of("research.market", String.class);
    static final StateKey<String> TECH = StateKey.of("research.tech", String.class);
    static final StateKey<String> PAID = StateKey.of("research.paid", String.class);

    private static final java.util.Set<String> RESEARCHERS =
            java.util.Set.of("market", "tech", "paid.analyst");

    private static final String RUN_ID = "brief-42";
    private static final String BRIEF_TEXT =
            "Should we build our agent runtime on the JVM or on Python?";

    public static void main(String[] args) {
        ChatClient chat = localOllamaClientOrNull();
        System.out.println("=== Recipe 10: Parallel research squad ===");
        System.out.println("[mode] " + (chat != null ? "LIVE (Ollama)" : "STUB") + "\n");

        AtomicInteger llmCalls = new AtomicInteger();
        // $0.50 per researcher and $2.00 for the whole run: the three branches
        // share one cap, and the estimator lets the gate refuse a call before it
        // is made rather than after.
        BudgetPolicy budget = BudgetPolicy.hierarchical(
                BudgetLimits.builder().perRun(2.00).build(),
                (node, ctx) -> RESEARCHERS.contains(node) ? 0.50 : 0.0,
                (node, result) -> RESEARCHERS.contains(node) ? 0.50 : 0.0);

        InMemoryRunLogStore runLog = new InMemoryRunLogStore();
        AgentGraph graph = AgentGraph.builder()
                .name("research-squad")
                .addNode("plan", ctx -> AgentResult.builder()
                        .text("plan ready")
                        .stateUpdates(Map.of(BRIEF, BRIEF_TEXT))
                        .completed(true)
                        .build())
                .addNode("market", researcher(chat, llmCalls, MARKET,
                        "Answer in one sentence: what do teams shipping agents in production choose today?"))
                .addNode("tech", researcher(chat, llmCalls, TECH,
                        "Answer in one sentence: what does the JVM give an agent runtime that Python does not?"))
                .addNode("paid.analyst", researcher(chat, llmCalls, PAID,
                        "Answer in one sentence: what would a paid analyst report say about this choice?"))
                .addNode("report", ParallelResearchDemo::report)
                // one fan-out: three researchers on the same brief
                .addEdge("plan", "market")
                .addEdge("plan", "tech")
                .addEdge("plan", "paid.analyst")
                // one join: the writer runs once, when all three are in
                .addEdge("market", "report")
                .addEdge("tech", "report")
                .addEdge("paid.analyst", "report")
                .maxConcurrency(3)
                .budgetPolicy(budget)
                // the paid source costs money: a human approves that branch only
                .approvalGate(ApprovalGate.requireFor("paid.analyst"))
                .checkpointStore(new InMemoryCheckpointStore())
                .runLog(runLog)
                .build();

        System.out.println("── first attempt: the paid branch waits for approval");
        AgentResult paused = graph.invoke(
                AgentContext.of(BRIEF_TEXT).with(BRIEF, BRIEF_TEXT), RunOptions.ofRunId(RUN_ID));
        System.out.println("  interrupted: " + paused.interrupt().reason());
        printLog(graph, runLog);

        System.out.println("\n── the analyst is approved: only that branch runs");
        AgentResult done = graph.resume(RUN_ID, ResumeOptions.ofApproval("paid.analyst"));
        System.out.println(done.text());
        printLog(graph, runLog);

        System.out.printf(java.util.Locale.ROOT, "%nllm calls: %d | researchers spent $%.2f of the $2.00 run cap%n",
                llmCalls.get(), budget.spent(BudgetPolicy.Scope.RUN, "market"));
    }

    /** A researcher writes its finding under its own key, so branches never collide. */
    private static Agent researcher(@Nullable ChatClient chat, AtomicInteger llmCalls,
                                    StateKey<String> outputKey, String question) {
        return ctx -> {
            String finding;
            if (chat != null) {
                llmCalls.incrementAndGet();
                finding = chat.prompt()
                        .system("You are a research analyst. One sentence, no preamble.")
                        .user(ctx.get(BRIEF) + "\n\n" + question)
                        .call()
                        .content();
            }
            else {
                finding = "[stub] " + question;
            }
            return AgentResult.builder()
                    .text(finding.strip())
                    .stateUpdates(Map.of(outputKey, finding.strip()))
                    .completed(true)
                    .build();
        };
    }

    /** The join: it sees the state of every branch that completed. */
    private static AgentResult report(AgentContext ctx) {
        StringBuilder sb = new StringBuilder("REPORT\n");
        sb.append("  market: ").append(ctx.get(MARKET)).append('\n');
        sb.append("  tech:   ").append(ctx.get(TECH)).append('\n');
        sb.append("  paid:   ").append(ctx.get(PAID));
        return AgentResult.ofText(sb.toString());
    }

    private static void printLog(AgentGraph graph, InMemoryRunLogStore runLog) {
        List<String> steps = graph.runLog(RUN_ID).stream()
                .filter(e -> e.type() == RunEventType.NODE_EXIT || e.type() == RunEventType.NODE_SKIPPED)
                .map(e -> e.type() == RunEventType.NODE_SKIPPED ? e.node() + " (skipped)" : e.node())
                .toList();
        System.out.println("  run log: " + String.join(", ", steps));
        runLog.clear(RUN_ID);
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
            return ChatClient.builder(chat)
                    .defaultOptions(OllamaOptions.builder().model(model).build())
                    .build();
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
