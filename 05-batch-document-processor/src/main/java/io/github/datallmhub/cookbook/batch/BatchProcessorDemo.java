package io.github.datallmhub.cookbook.batch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
 * Recipe 05 — Batch document processor with resume-after-crash semantics.
 *
 * <p>Iterates over a list of documents and runs each one through a small
 * agent graph ({@code analyze → tag}). After every successful doc the index
 * is persisted to disk; on restart, already-processed docs are skipped.
 *
 * <p>Simulate a crash by killing the process with Ctrl-C halfway through —
 * then re-run and the loop will resume where it stopped.
 */
public class BatchProcessorDemo {

    static final StateKey<String>       SUMMARY = StateKey.of("doc.summary", String.class);
    static final StateKey<List<String>> TAGS    = StateKey.of("doc.tags",    (Class<List<String>>) (Class<?>) List.class);

    private static final List<String> DOCUMENTS = List.of(
            "AgentFlow4J ships a BudgetPolicy SPI to cap multi-agent runs by cost.",
            "Spring AI 1.0 unifies chat models behind a single ChatClient interface.",
            "Resilience4j integrates seamlessly with Spring Boot for circuit breakers.",
            "LangChain4j gives Java developers an ergonomic LLM API similar to Python's LangChain.",
            "Apache Kafka 4.0 removed ZooKeeper in favour of KRaft consensus.",
            "OpenJDK 21 stabilised virtual threads, lowering the cost of blocking I/O.",
            "Quarkus and Spring Boot both support native compilation via GraalVM."
    );

    private static final Path PROGRESS_FILE = Path.of("target", "batch-progress.txt");

    public static void main(String[] args) throws IOException {
        ChatClient chat = localOllamaClientOrNull();
        AgentGraph graph = buildGraph(chat);

        int resumeFrom = readResumeIndex();

        System.out.println("=== Batch document processor ===");
        System.out.println("[mode]      " + (chat != null ? "LIVE (Ollama)" : "STUB"));
        System.out.println("[progress]  " + DOCUMENTS.size() + " documents, resuming from #" + resumeFrom + "\n");

        for (int i = resumeFrom; i < DOCUMENTS.size(); i++) {
            String doc = DOCUMENTS.get(i);
            String runId = "doc-" + i;
            System.out.println("─ Document #" + i + " (runId=" + runId + ")");
            System.out.println("  body: " + doc);

            AgentResult result = graph.invoke(AgentContext.of(doc), runId);

            System.out.println(result.text());
            writeResumeIndex(i + 1);
            System.out.println("  ✓ persisted progress (next start = #" + (i + 1) + ")\n");
        }

        System.out.println("All " + DOCUMENTS.size() + " documents processed.");
        System.out.println("Delete " + PROGRESS_FILE + " to re-run from the beginning.");
    }

    public static AgentGraph buildGraph(@Nullable ChatClient chat) {
        Agent analyze = chat != null ? llmAnalyze(chat) : stubAnalyze();
        Agent tag     = chat != null ? llmTag(chat)     : stubTag();

        return AgentGraph.builder()
                .name("batch-doc")
                .addNode("analyze", analyze)
                .addNode("tag",     tag)
                .addEdge("analyze", "tag")
                .build();
    }

    private static Agent llmAnalyze(ChatClient chat) {
        return ctx -> {
            String summary = chat.prompt()
                    .system("Summarise the document in one short sentence.")
                    .user(lastUser(ctx))
                    .call()
                    .content();
            return AgentResult.builder()
                    .stateUpdates(Map.of(SUMMARY, summary.trim()))
                    .completed(true)
                    .build();
        };
    }

    private static Agent llmTag(ChatClient chat) {
        return ctx -> {
            String reply = chat.prompt()
                    .system("List 2-4 single-word lowercase tags for this document, separated by commas. "
                          + "No explanations.")
                    .user(lastUser(ctx))
                    .call()
                    .content();
            List<String> tags = List.of(reply.toLowerCase().split("\\s*,\\s*"));
            String summary = ctx.get(SUMMARY);
            return AgentResult.builder()
                    .text("  summary: " + summary + "\n  tags:    " + tags)
                    .stateUpdates(Map.of(TAGS, tags))
                    .completed(true)
                    .build();
        };
    }

    private static Agent stubAnalyze() {
        return ctx -> {
            String body = lastUser(ctx);
            String summary = body.length() <= 60 ? body : body.substring(0, 57) + "...";
            return AgentResult.builder()
                    .stateUpdates(Map.of(SUMMARY, summary))
                    .completed(true)
                    .build();
        };
    }

    private static Agent stubTag() {
        return ctx -> {
            String t = lastUser(ctx).toLowerCase();
            List<String> tags = new java.util.ArrayList<>();
            if (t.contains("spring")) tags.add("spring");
            if (t.contains("java"))   tags.add("java");
            if (t.contains("llm") || t.contains("ai")) tags.add("ai");
            if (t.contains("kafka"))  tags.add("kafka");
            if (tags.isEmpty()) tags.add("misc");
            String summary = ctx.get(SUMMARY);
            return AgentResult.builder()
                    .text("  summary: " + summary + "\n  tags:    " + tags)
                    .stateUpdates(Map.of(TAGS, List.copyOf(tags)))
                    .completed(true)
                    .build();
        };
    }

    private static int readResumeIndex() {
        try {
            if (!Files.exists(PROGRESS_FILE)) return 0;
            return Integer.parseInt(Files.readString(PROGRESS_FILE).trim());
        }
        catch (Throwable t) {
            return 0;
        }
    }

    private static void writeResumeIndex(int i) throws IOException {
        Files.createDirectories(PROGRESS_FILE.getParent());
        Files.writeString(PROGRESS_FILE, Integer.toString(i));
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
}
