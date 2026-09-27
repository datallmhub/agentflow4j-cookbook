package io.github.datallmhub.cookbook.openhands;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.AgentListener;
import io.github.datallmhub.agentflow4j.graph.ApprovalGate;
import io.github.datallmhub.agentflow4j.graph.ApprovalRequest;
import io.github.datallmhub.agentflow4j.graph.Checkpoint;
import io.github.datallmhub.agentflow4j.graph.InMemoryCheckpointStore;
import io.github.datallmhub.agentflow4j.graph.InMemoryRunLogStore;
import io.github.datallmhub.agentflow4j.graph.ResumeOptions;
import io.github.datallmhub.agentflow4j.graph.RunOptions;
import io.github.datallmhub.agentflow4j.openhands.OpenHandsAgent;
import io.github.datallmhub.agentflow4j.openhands.OpenHandsClient;
import io.github.datallmhub.agentflow4j.openhands.OpenHandsKeys;

/**
 * Recipe 11: a governed OpenHands workflow.
 *
 * <p>A ticket becomes a coding task delegated to OpenHands, with af4j as the
 * governance layer around it:
 *
 * <ol>
 *   <li><b>triage</b> turns the ticket into a task description;</li>
 *   <li>an {@link ApprovalGate} stops before the paid coding run, so a human
 *       approves the spend;</li>
 *   <li><b>code</b> is an {@link OpenHandsAgent} in async mode: it starts the
 *       conversation and interrupts, the graph checkpoints, nothing blocks;</li>
 *   <li>a scheduler (here a loop) resumes the run until OpenHands is done, and
 *       each resume polls the same conversation, never a new one;</li>
 *   <li><b>announce</b> reports the pull request the agent opened.</li>
 * </ol>
 *
 * <p>Runs with no account: a local stub answers the OpenHands V1 endpoints.
 * Set {@code OPENHANDS_API_KEY} (and optionally {@code OPENHANDS_REPOSITORY})
 * to run it against OpenHands Cloud for real.
 */
public class GovernedOpenHandsDemo {

    static final StateKey<String> TICKET = StateKey.of("ticket", String.class);
    static final StateKey<String> TASK = StateKey.of("coding.task", String.class);

    private static final String RUN_ID = "ticket-1042";
    private static final String TICKET_TEXT =
            "Invoice totals are off by one cent on orders with a discount.";

    public static void main(String[] args) {
        String apiKey = System.getenv("OPENHANDS_API_KEY");
        String repository = envOr("OPENHANDS_REPOSITORY", "acme/billing");

        System.out.println("=== Recipe 11: Governed OpenHands workflow ===");
        System.out.println("[mode] " + (apiKey != null ? "LIVE (OpenHands Cloud)" : "STUB (local server)") + "\n");

        // The stub walks the conversation: working, working, then done.
        try (LocalOpenHandsStub stub = apiKey == null
                ? new LocalOpenHandsStub(0.35, "running", "running", "finished") : null) {
            OpenHandsClient client = apiKey != null
                    ? OpenHandsClient.cloud(apiKey)
                    : OpenHandsClient.of(stub.baseUrl(), "stub-key");
            run(client, repository);
        }
    }

    private static void run(OpenHandsClient client, String repository) {
        OpenHandsAgent coder = OpenHandsAgent.builder()
                .name("code")
                .client(client)
                .repository(repository)
                .task(ctx -> ctx.get(TASK))
                .mode(OpenHandsAgent.Mode.ASYNC)   // the default: interrupt instead of blocking
                .pollInterval(Duration.ofMillis(200))
                .maxCost(5.00)                     // above this, pause the sandbox and interrupt
                .build();

        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        AgentGraph graph = AgentGraph.builder()
                .name("ticket-to-pr")
                .addNode("triage", ctx -> AgentResult.builder()
                        .text("coding task ready")
                        .stateUpdates(Map.of(TASK,
                                "Fix: " + ctx.get(TICKET) + " Add a regression test for the discount rounding."))
                        .completed(true)
                        .build())
                .addNode("code", coder)
                .addNode("announce", ctx -> AgentResult.ofText(
                        String.format(java.util.Locale.ROOT, "PR #%d on branch %s (cost $%.2f)",
                                ctx.get(OpenHandsKeys.PULL_REQUEST), ctx.get(OpenHandsKeys.BRANCH),
                                ctx.get(OpenHandsKeys.COST))))
                .addEdge("triage", "code")
                .addEdge("code", "announce")
                // a human approves before the paid coding run starts
                .approvalGate(ApprovalGate.requireFor("code"))
                // required in async mode: the interrupt is resumed from the checkpoint
                .checkpointStore(checkpoints)
                .runLog(new InMemoryRunLogStore())
                .listener(new AgentListener() {
                    @Override
                    public void onApprovalRequired(String graphName, ApprovalRequest request) {
                        System.out.println("  [approval] " + request.nodeName() + ": " + request.reason());
                    }

                    @Override
                    public void onCheckpoint(String graphName, Checkpoint checkpoint) {
                        System.out.println("  [checkpoint] next=" + checkpoint.nextNodes()
                                + " conversation=" + checkpoint.context().get(OpenHandsKeys.CONVERSATION_ID));
                    }
                })
                .build();

        System.out.println("── the ticket arrives");
        AgentResult result = graph.invoke(
                AgentContext.of(TICKET_TEXT).with(TICKET, TICKET_TEXT), RunOptions.ofRunId(RUN_ID));
        System.out.println("  interrupted: " + result.interrupt().reason() + "\n");

        System.out.println("── a human approves the spend");
        result = graph.resume(RUN_ID, ResumeOptions.ofApproval("code"));
        System.out.println("  interrupted: " + result.interrupt().reason() + "\n");

        System.out.println("── a scheduler resumes the run while OpenHands works");
        int poll = 0;
        while (result.isInterrupted() && poll++ < 10) {
            sleep(Duration.ofMillis(200));
            result = graph.resume(RUN_ID, ResumeOptions.none());
            System.out.println("  poll " + poll + ": "
                    + (result.isInterrupted() ? result.interrupt().reason() : "done"));
        }

        System.out.println("\n" + result.text());
        System.out.println("\n  run log: " + graph.runLog(RUN_ID).stream()
                .map(e -> e.type() + (e.node() == null ? "" : "(" + e.node() + ")"))
                .reduce((a, b) -> a + ", " + b)
                .orElse(""));
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
