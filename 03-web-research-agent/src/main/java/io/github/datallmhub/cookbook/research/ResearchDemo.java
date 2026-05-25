package io.github.datallmhub.cookbook.research;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Recipe 03 — Web research agent.
 *
 * <p>A three-agent graph that pulls real data from Hacker News:
 * <pre>
 *   search  →  rank  →  synthesize
 * </pre>
 *
 * <p>{@code search} hits the public HN Algolia API. {@code rank} keeps the top-K
 * by points. {@code synthesize} asks the LLM for a one-paragraph digest with
 * source links — or falls back to a deterministic bullet list if no LLM is
 * available.
 */
public class ResearchDemo {

    record Hit(String title, String url, int points) {}

    static final StateKey<List<Hit>> HITS = StateKey.of("research.hits", (Class<List<Hit>>) (Class<?>) List.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        ChatClient chat = localOllamaClientOrNull();
        AgentGraph graph = buildGraph(chat);

        String query = args.length > 0 ? String.join(" ", args) : "java llm agents";

        System.out.println("=== Web research agent ===");
        System.out.println("[mode]      " + (chat != null ? "LIVE (Ollama)" : "STUB") + "\n");
        System.out.println("Query:      " + query + "\n");

        AgentResult result = graph.invoke(AgentContext.of(query));

        System.out.println("\nDigest:");
        System.out.println("─".repeat(60));
        System.out.println(result.text());
        System.out.println("─".repeat(60));
    }

    public static AgentGraph buildGraph(@Nullable ChatClient chat) {
        Agent search = ctx -> {
            String query = lastUser(ctx);
            List<Hit> raw = hackerNewsSearch(query);
            System.out.println("[search]    HN returned " + raw.size() + " hits");
            return AgentResult.builder()
                    .stateUpdates(Map.of(HITS, raw))
                    .completed(true)
                    .build();
        };

        Agent rank = ctx -> {
            List<Hit> raw = ctx.get(HITS);
            List<Hit> top = raw == null ? List.of() : raw.stream()
                    .sorted((a, b) -> Integer.compare(b.points, a.points))
                    .limit(5)
                    .toList();
            System.out.println("[rank]      kept top " + top.size() + " by points");
            return AgentResult.builder()
                    .stateUpdates(Map.of(HITS, top))
                    .completed(true)
                    .build();
        };

        Agent synthesize = chat != null ? llmSynthesize(chat) : stubSynthesize();

        return AgentGraph.builder()
                .name("web-research")
                .addNode("search",     search)
                .addNode("rank",       rank)
                .addNode("synthesize", synthesize)
                .addEdge("search",     "rank")
                .addEdge("rank",       "synthesize")
                .build();
    }

    private static List<Hit> hackerNewsSearch(String query) {
        try {
            String url = "https://hn.algolia.com/api/v1/search?tags=story&query="
                       + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return List.of();
            JsonNode root = JSON.readTree(resp.body());
            return root.path("hits").findValues("title").isEmpty()
                    ? List.of()
                    : streamHits(root);
        }
        catch (Throwable t) {
            System.err.println("HN search failed: " + t.getMessage());
            return List.of();
        }
    }

    private static List<Hit> streamHits(JsonNode root) {
        return java.util.stream.StreamSupport.stream(root.path("hits").spliterator(), false)
                .map(n -> new Hit(
                        n.path("title").asText(""),
                        n.path("url").asText(""),
                        n.path("points").asInt(0)))
                .filter(h -> !h.title.isBlank())
                .toList();
    }

    private static Agent llmSynthesize(ChatClient chat) {
        return ctx -> {
            List<Hit> hits = ctx.get(HITS);
            if (hits == null || hits.isEmpty()) {
                return AgentResult.ofText("No relevant results found on Hacker News.");
            }
            String sources = hits.stream()
                    .map(h -> "- (" + h.points + " pts) " + h.title + " — " + h.url)
                    .collect(Collectors.joining("\n"));
            String digest = chat.prompt()
                    .system("Summarise the following Hacker News stories into one short paragraph. "
                          + "Be factual, neutral, and reference the most relevant story by title.")
                    .user("Topic: " + lastUser(ctx) + "\n\nStories:\n" + sources)
                    .call()
                    .content();
            return AgentResult.ofText(digest + "\n\nSources:\n" + sources);
        };
    }

    private static Agent stubSynthesize() {
        return ctx -> {
            List<Hit> hits = ctx.get(HITS);
            if (hits == null || hits.isEmpty()) {
                return AgentResult.ofText("No relevant results found on Hacker News.");
            }
            return AgentResult.ofText("Top stories on Hacker News:\n\n" + hits.stream()
                    .map(h -> "  • [" + h.points + " pts] " + h.title + "\n    " + h.url)
                    .collect(Collectors.joining("\n")));
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
                    .defaultOptions(OllamaOptions.builder().model(model).temperature(0.3).build())
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
