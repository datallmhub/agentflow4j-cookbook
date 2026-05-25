package io.github.datallmhub.cookbook.rag;

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
 * Recipe 01 — RAG agent.
 *
 * <p>A two-agent graph:
 * <pre>
 *   retrieve  →  answer
 * </pre>
 *
 * <p>{@code retrieve} looks up relevant docs from an in-memory knowledge base
 * (keyword scoring — swap in a real {@code VectorStore} for production).
 * {@code answer} composes a grounded reply using the retrieved snippets.
 *
 * <p>Run with a local Ollama daemon (chat model: {@code llama3.2:3b}). Without
 * Ollama the demo falls back to a deterministic stub so anyone can
 * clone-and-go.
 */
public class RagAgentDemo {

    static final StateKey<List<Doc>> RETRIEVED = StateKey.of("rag.retrieved", (Class<List<Doc>>) (Class<?>) List.class);

    record Doc(String id, String title, String body) {}

    private static final List<Doc> KNOWLEDGE_BASE = List.of(
            new Doc("af4j-001", "AgentFlow4J overview",
                    "AgentFlow4J is a Java framework for governed multi-agent LLM workflows. "
                  + "It runs on top of Spring AI and provides durable graph execution, "
                  + "checkpointing, and policy-based governance."),
            new Doc("af4j-002", "Governance gates",
                    "AgentFlow4J ships four governance gates: BudgetPolicy caps cost per run, "
                  + "node, or call; ToolPolicy controls which tools an agent may invoke; "
                  + "StatePolicy restricts which keys a node may write; ApprovalGate pauses "
                  + "the run for human approval."),
            new Doc("af4j-003", "Checkpointing",
                    "Every run can be persisted by attaching a CheckpointStore. After a crash "
                  + "or a human pause, graph.resumeWithApproval(runId) replays from the last "
                  + "successful node — no work is lost."),
            new Doc("af4j-004", "Run log",
                    "Attach a RunLogStore to record every node enter/exit, transition, and "
                  + "governance event. The log is the data foundation for replay debuggers "
                  + "and OpenTelemetry export."),
            new Doc("af4j-005", "Spring AI integration",
                    "The agentflow4j-starter module auto-configures every ChatModel provided "
                  + "by Spring AI: OpenAI, Mistral, Anthropic, Ollama, Vertex AI. Switching "
                  + "providers requires changing one starter dependency.")
    );

    public static void main(String[] args) {
        ChatClient chat = localOllamaClientOrNull();
        AgentGraph graph = buildGraph(chat);

        String question = args.length > 0
                ? String.join(" ", args)
                : "How does AgentFlow4J prevent an agent from burning my budget?";

        System.out.println("=== RAG agent ===");
        System.out.println("[mode]      " + (chat != null ? "LIVE (Ollama)" : "STUB (no Ollama at " + envOr("OLLAMA_HOST", "http://localhost:11434") + ")") + "\n");
        System.out.println("Question:   " + question + "\n");

        AgentResult result = graph.invoke(AgentContext.of(question));

        System.out.println("\nAnswer:");
        System.out.println("─".repeat(60));
        System.out.println(result.text());
        System.out.println("─".repeat(60));
    }

    public static AgentGraph buildGraph(@Nullable ChatClient chat) {
        Agent retrieve = ctx -> {
            String query = lastUser(ctx);
            List<Doc> hits = scoreAndRank(query, 3);
            System.out.println("[retrieve]  query=\"" + query + "\" → " + hits.size() + " hits");
            hits.forEach(d -> System.out.println("              · " + d.id + " — " + d.title));
            return AgentResult.builder()
                    .text("retrieved " + hits.size() + " documents")
                    .stateUpdates(Map.of(RETRIEVED, hits))
                    .completed(true)
                    .build();
        };

        Agent answer = chat != null ? llmAnswer(chat) : stubAnswer();

        return AgentGraph.builder()
                .name("rag-agent")
                .addNode("retrieve", retrieve)
                .addNode("answer",   answer)
                .addEdge("retrieve", "answer")
                .build();
    }

    private static Agent llmAnswer(ChatClient chat) {
        return ctx -> {
            List<Doc> hits = ctx.get(RETRIEVED);
            String context = hits == null ? "" : hits.stream()
                    .map(d -> "[" + d.id + "] " + d.title + "\n" + d.body)
                    .collect(Collectors.joining("\n\n"));

            String reply = chat.prompt()
                    .system("Answer the user's question using ONLY the provided context. "
                          + "Cite source ids in square brackets, e.g. [af4j-001]. "
                          + "If the context does not contain the answer, say so plainly.\n\n"
                          + "Context:\n" + context)
                    .user(lastUser(ctx))
                    .call()
                    .content();

            System.out.println("[answer]    Ollama drafted (" + reply.length() + " chars)");
            return AgentResult.ofText(reply);
        };
    }

    private static Agent stubAnswer() {
        return ctx -> {
            List<Doc> hits = ctx.get(RETRIEVED);
            String body = hits == null || hits.isEmpty()
                    ? "I could not find any relevant information in the knowledge base."
                    : "Based on the retrieved documents:\n\n" + hits.stream()
                            .map(d -> "  • [" + d.id + "] " + d.body)
                            .collect(Collectors.joining("\n"));
            System.out.println("[answer]    stub drafted");
            return AgentResult.ofText(body);
        };
    }

    static List<Doc> scoreAndRank(String query, int topK) {
        List<String> terms = List.of(query.toLowerCase().split("\\W+"));
        return KNOWLEDGE_BASE.stream()
                .map(d -> Map.entry(d, score(d, terms)))
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static int score(Doc d, List<String> terms) {
        String haystack = (d.title + " " + d.body).toLowerCase();
        int s = 0;
        for (String t : terms) {
            if (t.length() < 3) continue;
            int i = 0;
            while ((i = haystack.indexOf(t, i)) != -1) { s++; i += t.length(); }
        }
        return s;
    }

    /**
     * Builds a Spring AI ChatClient against a local Ollama daemon.
     *
     * <p>Configured via two env vars with sensible defaults:
     * <ul>
     *   <li>{@code OLLAMA_HOST}  (default {@code http://localhost:11434})</li>
     *   <li>{@code OLLAMA_MODEL} (default {@code llama3.2:3b})</li>
     * </ul>
     */
    @Nullable
    private static ChatClient localOllamaClientOrNull() {
        String baseUrl = envOr("OLLAMA_HOST",  "http://localhost:11434");
        String model   = envOr("OLLAMA_MODEL", "llama3.2:3b");

        int port = portFromUrl(baseUrl);
        if (!isPortOpen(hostFromUrl(baseUrl), port, 500)) return null;

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
        try {
            int p = java.net.URI.create(url).getPort();
            return p > 0 ? p : 11434;
        }
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
