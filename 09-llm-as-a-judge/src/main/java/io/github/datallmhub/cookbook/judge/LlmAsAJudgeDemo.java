package io.github.datallmhub.cookbook.judge;

import java.util.List;
import java.util.Map;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.AgentRunEvent;
import io.github.datallmhub.agentflow4j.graph.BudgetLimits;
import io.github.datallmhub.agentflow4j.graph.BudgetPolicy;
import io.github.datallmhub.agentflow4j.graph.Edge;
import io.github.datallmhub.agentflow4j.graph.InMemoryRunLogStore;
import io.github.datallmhub.agentflow4j.graph.RunEventType;
import io.github.datallmhub.agentflow4j.graph.RunOptions;
import io.github.datallmhub.agentflow4j.squad.ExecutorAgent;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;

/**
 * Recipe 09: LLM as a judge.
 *
 * <p>A support answer is scored by a second agent whose output is a typed
 * record, not prose: {@link Verdict}. The graph then routes on the score, which
 * is what makes the judge useful rather than decorative:
 *
 * <ul>
 *   <li>a good answer is sent;</li>
 *   <li>a weak answer goes back to the writer with the judge's reasons;</li>
 *   <li>an answer the judge flags as risky goes to a human.</li>
 * </ul>
 *
 * <p>The judging itself costs money, so a {@link BudgetPolicy} caps it, and the
 * run log records every verdict. Use a judge for what code cannot check (tone,
 * faithfulness, risk); keep deterministic rules in code, as in recipe 08.
 *
 * <p>Runs with no setup: with Ollama the judge is a real model, otherwise a
 * stub scores deterministically.
 */
public class LlmAsAJudgeDemo {

    /** The judge's structured output: Spring AI maps the model's JSON onto this record. */
    public record Verdict(int score, boolean risky, List<String> reasons) {}

    static final StateKey<String> QUESTION = StateKey.of("question", String.class);
    static final StateKey<String> ANSWER = StateKey.of("answer", String.class);
    static final StateKey<Verdict> VERDICT = StateKey.of("verdict", Verdict.class);
    static final StateKey<Integer> ROUNDS = StateKey.of("judge.rounds", Integer.class);

    private static final int PASS_SCORE = 7;
    private static final int MAX_ROUNDS = 2;

    private static final String JUDGE_PROMPT = """
            You review customer support answers. Reply with JSON only:
            score (1-10 for helpfulness and accuracy), risky (true when the answer promises
            a refund, a legal outcome or personal data), reasons (short strings).
            Review the assistant's most recent answer in the conversation.""";

    public static void main(String[] args) {
        ChatClient chat = localOllamaClientOrNull();
        System.out.println("=== Recipe 09: LLM as a judge ===");
        System.out.println("[mode] " + (chat != null ? "LIVE (Ollama)" : "STUB") + "\n");

        review(chat, "q-1", "My parcel is two days late, where is it?");
        review(chat, "q-2", "I want my money back for the broken lamp.");
    }

    private static void review(@Nullable ChatClient chat, String runId, String question) {
        // A run budget belongs to one run: build it per run, not once for the process.
        BudgetPolicy budget = BudgetPolicy.hierarchical(
                BudgetLimits.builder().perRun(0.50).build(),
                (node, ctx) -> "judge".equals(node) ? 0.10 : 0.0,
                (node, result) -> "judge".equals(node) ? 0.10 : 0.0);

        InMemoryRunLogStore runLog = new InMemoryRunLogStore();
        ExecutorAgent judge = ExecutorAgent.builder()
                .name("judge")
                .chatClient(chat != null ? chat : ChatClient.create(new ScriptedJudgeModel()))
                .systemPrompt(JUDGE_PROMPT)
                .outputKey(VERDICT)          // the verdict lands in typed state
                .build();

        AgentGraph graph = AgentGraph.builder()
                .name("answer-review")
                .addNode("write", writer(chat))
                .addNode("judge", judge)
                .addNode("send", ctx -> AgentResult.ofText("SENT: " + ctx.get(ANSWER)))
                .addNode("escalate", ctx -> AgentResult.ofText(
                        "TO A HUMAN: " + reasons(ctx) + " | draft: " + ctx.get(ANSWER)))
                .addEdge("write", "judge")
                .addEdge(Edge.conditional("judge", ctx -> ctx.get(VERDICT).risky(), "escalate"))
                .addEdge(Edge.conditional("judge", ctx -> ctx.get(VERDICT).score() >= PASS_SCORE, "send"))
                .addEdge(Edge.conditional("judge", ctx -> ctx.get(ROUNDS) >= MAX_ROUNDS, "escalate"))
                .addEdge("judge", "write")   // fallback: another round with the judge's reasons
                .maxIterations(2 * MAX_ROUNDS + 2)
                .budgetPolicy(budget)
                .runLog(runLog)
                .build();

        System.out.println("── " + runId + ": " + question);
        AgentResult result = graph.invoke(
                AgentContext.of(question).with(QUESTION, question), RunOptions.ofRunId(runId));
        System.out.println("  " + result.text());
        String path = graph.runLog(runId).stream()
                .filter(e -> e.type() == RunEventType.NODE_EXIT)
                .map(AgentRunEvent::node)
                .reduce((a, b) -> a + " -> " + b)
                .orElse("");
        System.out.println("  run log: " + path);
        System.out.printf(java.util.Locale.ROOT, "  judging cost $%.2f of the $0.50 run cap%n%n",
                budget.spent(BudgetPolicy.Scope.RUN, "judge"));
    }

    /** The writer: it revises when the judge sent reasons back. */
    private static Agent writer(@Nullable ChatClient chat) {
        return ctx -> {
            int round = ctx.get(ROUNDS) == null ? 1 : ctx.get(ROUNDS) + 1;
            Verdict previous = ctx.get(VERDICT);
            String answer;
            if (chat != null) {
                String instruction = previous == null
                        ? "Answer this customer in two sentences: " + ctx.get(QUESTION)
                        : "Improve this answer (" + String.join("; ", previous.reasons()) + "): " + ctx.get(ANSWER);
                answer = chat.prompt().system("You are a support agent. Two sentences, no promises.")
                        .user(instruction).call().content();
            }
            else {
                answer = stubAnswer(ctx.get(QUESTION), round);
            }
            System.out.printf("  [write #%d] %s%n", round, answer.strip());
            return AgentResult.builder()
                    .text(answer.strip())
                    .stateUpdates(Map.of(ANSWER, answer.strip(), ROUNDS, round))
                    .completed(true)
                    .build();
        };
    }

    private static String stubAnswer(String question, int round) {
        if (question.toLowerCase().contains("money back")) {
            return "We will refund you in full today, guaranteed.";
        }
        return round == 1
                ? "It is late."
                : "Your parcel is delayed by the carrier and is expected within 48 hours; "
                  + "here is the tracking link so you can follow it.";
    }

    private static String reasons(AgentContext ctx) {
        Verdict verdict = ctx.get(VERDICT);
        return verdict == null ? "no verdict" : String.join("; ", verdict.reasons());
    }

    /**
     * Stands in for the judging model: it returns the JSON that Spring AI maps
     * onto {@link Verdict}, scoring on the same signals a real judge would.
     */
    static final class ScriptedJudgeModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            // The answer under review is the writer's message, not the user's question.
            String answer = lastAssistantText(prompt.getInstructions()).toLowerCase();
            String json;
            if (answer.contains("refund") || answer.contains("guaranteed")) {
                json = """
                        {"score":4,"risky":true,"reasons":["promises a refund","uses 'guaranteed'"]}""";
            }
            else if (answer.length() < 40) {
                json = """
                        {"score":5,"risky":false,"reasons":["too short","no tracking information"]}""";
            }
            else {
                json = """
                        {"score":9,"risky":false,"reasons":["accurate","actionable"]}""";
            }
            System.out.println("  [judge   ] " + json);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        }

        private static String lastAssistantText(List<Message> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof AssistantMessage assistant
                        && assistant.getText() != null && !assistant.getText().isBlank()
                        && !assistant.getText().trim().startsWith("{")) {   // skip an earlier verdict
                    return assistant.getText();
                }
            }
            return "";
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
