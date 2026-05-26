package io.github.datallmhub.cookbook.routing;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.BudgetExceededException;
import io.github.datallmhub.agentflow4j.graph.BudgetLimits;
import io.github.datallmhub.agentflow4j.graph.BudgetPolicy;
import io.github.datallmhub.agentflow4j.graph.CostEstimator;
import io.github.datallmhub.agentflow4j.graph.CostMeter;
import io.github.datallmhub.agentflow4j.graph.FailureClassification;
import io.github.datallmhub.agentflow4j.graph.FailureClassifier;
import io.github.datallmhub.agentflow4j.graph.RetryPolicy;
import io.github.datallmhub.agentflow4j.squad.CoordinatorAgent;
import io.github.datallmhub.agentflow4j.squad.RoutingStrategy;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;

/**
 * Recipe 06 — Cost-aware routing &amp; reason-aware retries.
 *
 * <p>Two levers AgentFlow4J gives you to keep an agentic workload from quietly
 * burning money:
 *
 * <ol>
 *   <li><b>Scene A — budget-aware routing.</b> A {@link CoordinatorAgent} sends
 *       work to a <em>premium</em> model while there is budget left, then
 *       <em>degrades gracefully</em> to a cheaper <em>fallback</em> once the
 *       remaining run budget drops below a threshold. The decision is
 *       deterministic and free: it reads the live {@link BudgetPolicy} counters,
 *       no extra LLM call to "classify complexity".</li>
 *   <li><b>Scene B — reason-aware retries.</b> A {@link RetryPolicy} carrying a
 *       {@link FailureClassifier} retries only the failures worth retrying
 *       (a transient blip, honouring a {@code Retry-After} hint), gives up
 *       immediately on a permanent error, and surfaces budget exhaustion as an
 *       interrupt instead of hammering the provider.</li>
 * </ol>
 *
 * <p>Runs with no setup: the answering agents use a local Ollama model when one
 * is reachable, otherwise a deterministic stub. The routing and retry mechanics
 * are identical either way.
 */
public class CostAwareRoutingDemo {

    public static void main(String[] args) {
        ChatClient chat = localOllamaClientOrNull();
        System.out.println("=== Recipe 06 — Cost-aware routing ===");
        System.out.println("[mode] " + (chat != null ? "LIVE (Ollama)" : "STUB") + "\n");

        sceneCostAwareRouting(chat);
        sceneReasonAwareRetry();
    }

    // ── Scene A ──────────────────────────────────────────────────────────────
    // Premium while budget allows, then fall back to the cheap model.

    private static void sceneCostAwareRouting(@Nullable ChatClient chat) {
        System.out.println("── Scene A: budget-aware routing ─────────────────────────────");

        // A run budget of $5.00. We drive spend with the meter below, so the
        // estimator can stay at zero (no pre-call gating needed here).
        // Premium answers cost $1.50; the fallback model costs $0.25.
        CostMeter meter = (node, result) -> "premium".equals(node) ? 1.50 : 0.25;
        BudgetPolicy budget = BudgetPolicy.hierarchical(
                BudgetLimits.run(5.00), CostEstimator.zero(), meter);

        Agent premium  = answerAgent(chat, budget, "premium",  "gpt-4-class");
        Agent fallback = answerAgent(chat, budget, "fallback", "mini-class");

        // Once less than $2.00 remains, route to the cheaper executor.
        RoutingStrategy router = RoutingStrategy.budgetAware(
                budget, BudgetPolicy.Scope.RUN, 2.00, "premium", "fallback");

        CoordinatorAgent desk = CoordinatorAgent.builder()
                .name("ops-desk")
                .executor("premium", premium)
                .executor("fallback", fallback)
                .routingStrategy(router)
                .build();

        String[] tickets = {
                "Summarise our refund policy in one line.",
                "Draft a reply to a customer asking about GDPR data export.",
                "What is the capital of France?",
                "Explain our SLA for enterprise customers.",
                "Translate 'thank you for your patience' into German.",
                "Confirm the office is closed on public holidays.",
        };

        for (String ticket : tickets) {
            double left = budget.remaining(BudgetPolicy.Scope.RUN, "premium");
            System.out.printf("%nbudget left $%.2f | %s%n", left, ticket);
            AgentResult result = desk.execute(AgentContext.of(ticket));
            System.out.println("  ↳ " + result.text());
        }
        System.out.printf("%ntotal spent: $%.2f of $5.00%n%n",
                budget.spent(BudgetPolicy.Scope.RUN, "premium"));
    }

    /**
     * An answering agent that bills the shared budget when it runs — mirroring
     * how {@code AgentGraph} records a node's cost after each successful call.
     */
    private static Agent answerAgent(@Nullable ChatClient chat, BudgetPolicy budget,
                                     String tag, String modelLabel) {
        return ctx -> {
            String task = lastUser(ctx);
            String answer = chat != null
                    ? chat.prompt().system("Answer in one short sentence.").user(task).call().content()
                    : "[stub answer]";
            System.out.printf("  [%-8s %-10s] handled%n", tag, modelLabel);
            AgentResult result = AgentResult.ofText(tag.toUpperCase() + ": " + answer.strip());
            budget.record(tag, result);   // charge this call against the run budget
            return result;
        };
    }

    // ── Scene B ──────────────────────────────────────────────────────────────
    // Retry the transient, drop the permanent, interrupt on over-budget.

    private static void sceneReasonAwareRetry() {
        System.out.println("── Scene B: reason-aware retry ───────────────────────────────");

        // Domain rules first; anything we don't recognise falls through to the
        // framework defaults (IOExceptions → TRANSIENT, BudgetExceededException
        // → OVER_BUDGET, other HTTP 4xx → PERMANENT, ...).
        FailureClassifier domain = cause -> {
            if (cause instanceof RateLimited rl) {
                return FailureClassification.transientFailure(rl.retryAfter);
            }
            if (cause instanceof TransientGlitch) {
                return FailureClassification.transientFailure();
            }
            if (cause instanceof InvalidRequest) {
                return FailureClassification.permanent("provider rejected the request");
            }
            return null; // defer to the defaults
        };
        RetryPolicy retry = RetryPolicy.exponential(3, Duration.ofMillis(50))
                .withClassifier(domain.orElse(FailureClassifier.defaults()));

        // A node that fails twice with a transient glitch, then succeeds.
        AtomicInteger attempt = new AtomicInteger();
        Agent flaky = ctx -> {
            int n = attempt.incrementAndGet();
            if (n < 3) {
                System.out.println("  attempt " + n + " → transient glitch, will retry");
                throw new TransientGlitch("upstream 503");
            }
            System.out.println("  attempt " + n + " → success");
            return AgentResult.ofText("ingested after " + n + " attempts");
        };

        AgentGraph graph = AgentGraph.builder()
                .name("retry-demo")
                .addNode("ingest", flaky)
                .retryPolicy(retry)
                .build();

        AgentResult result = graph.invoke(AgentContext.of("payload"));
        System.out.println("  final → " + result.text() + "\n");

        // The same policy, asked how it would handle other failures:
        System.out.println("  how the policy classifies other failures:");
        classify(retry, new RateLimited("429 Too Many Requests", Duration.ofSeconds(2)));
        classify(retry, new InvalidRequest("malformed request body"));
        classify(retry, new BudgetExceededException("insufficient quota"));
    }

    private static void classify(RetryPolicy policy, Throwable cause) {
        FailureClassification c = policy.classify(cause);
        String hint = c.retryAfter() != null ? "  retryAfter=" + c.retryAfter().toSeconds() + "s" : "";
        String why  = c.reason() != null ? "  (" + c.reason() + ")" : "";
        System.out.printf("    %-26s → %-10s%s%s%n",
                cause.getClass().getSimpleName(), c.category(), hint, why);
    }

    // Simulated provider failures (a real app would get these from the SDK).
    static final class TransientGlitch extends RuntimeException {
        TransientGlitch(String m) { super(m); }
    }
    static final class RateLimited extends RuntimeException {
        final Duration retryAfter;
        RateLimited(String m, Duration retryAfter) { super(m); this.retryAfter = retryAfter; }
    }
    static final class InvalidRequest extends RuntimeException {
        InvalidRequest(String m) { super(m); }
    }

    // ── Ollama wiring (optional) ───────────────────────────────────────────────

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
}
