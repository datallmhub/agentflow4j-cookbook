# Recipe 01 — RAG agent in Java

> **Build a retrieval-augmented generation (RAG) pipeline as a two-agent graph in Java** with AgentFlow4J and Spring AI. Local-first (runs against Ollama), with a budget cap so a flaky retriever can't blow up your token budget.

---

## What you'll build

```
┌──────────┐       ┌──────────┐
│ retrieve │  ───▶ │  answer  │
└──────────┘       └──────────┘
   keyword          LLM (Ollama
   scoring          / OpenAI / …)
```

A **retrieve** agent looks up the 3 most relevant snippets from an in-memory knowledge base, then an **answer** agent composes a grounded reply citing source ids.

The shape is intentionally minimal — once you've got the two-agent graph wired up, swap the in-memory keyword retriever for a real vector store (Spring AI's `PgVectorStore`, `RedisVectorStore`, etc.) and you have a production RAG.

---

## Why two agents instead of one prompt?

A single mega-prompt couples retrieval, generation, and prompt engineering. The graph version gives you:

- **Independent observability** — the run log shows `retrieve` and `answer` as separate steps with their own latencies
- **Independent governance** — the `BudgetPolicy` knows the answer node is the expensive one; the retrieve node has its own cap
- **Independent retries** — a flaky LLM doesn't trigger a re-retrieve

This is the pattern multi-agent frameworks exist for.

---

## Run it

```bash
# from the cookbook root
mvn -pl 01-rag-agent exec:java

# with a custom question
mvn -pl 01-rag-agent exec:java -Dexec.args="What governance gates does AgentFlow4J provide?"
```

### Stub mode (no LLM)

If Ollama is not running on `localhost:11434`, the demo falls back to a stub answerer — you still see the retrieval, state propagation, and graph execution.

### Live mode (Ollama)

```bash
ollama pull llama3.2:3b
ollama serve
```

Re-run and you'll see `[mode] LIVE (Ollama)` plus an LLM-grounded answer with `[af4j-001]`-style citations.

---

## Key concepts demonstrated

| Concept | Where in the code |
|---|---|
| **Typed state propagation** | `StateKey<List<Doc>> RETRIEVED` — the retrieve agent writes hits, the answer agent reads them |
| **Graph composition** | `AgentGraph.builder().addNode(...).addEdge(...).build()` |
| **Hybrid LLM/stub agents** | `chat != null ? llmAnswer(chat) : stubAnswer()` — recipe works without an API key |
| **Spring AI ChatClient** | `chat.prompt().system(...).user(...).call().content()` — provider-agnostic |
| **Source citations** | The system prompt asks the LLM to cite `[af4j-001]`-style ids from the retrieved context |

---

## Switching to a real vector store

The `KNOWLEDGE_BASE` constant + `scoreAndRank()` are placeholders. To use a real vector store:

```java
// 1. Add the dependency (e.g. PgVectorStore)
//    <dependency>
//      <groupId>org.springframework.ai</groupId>
//      <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
//    </dependency>

// 2. Replace the retrieve agent body:
Agent retrieve = ctx -> {
    List<Document> hits = vectorStore.similaritySearch(
            SearchRequest.query(lastUser(ctx)).withTopK(3));
    return AgentResult.builder()
            .stateUpdates(Map.of(RETRIEVED_DOCS, hits))
            .completed(true)
            .build();
};
```

Everything else — the graph, the answer agent, the policies — stays unchanged.

---

## Switching to OpenAI / Mistral / Anthropic

In [`pom.xml`](pom.xml), replace `spring-ai-starter-model-ollama` with:

- `spring-ai-starter-model-openai`
- `spring-ai-starter-model-mistral-ai`
- `spring-ai-starter-model-anthropic`

Then update `ollamaChatClientOrNull()` to build the corresponding chat model. Or use Spring's auto-configuration with [`agentflow4j-starter`](https://github.com/datallmhub/agentflow4j) and let `@Autowired ChatClient` pick it up from `application.yml`.

---

## Adding a budget cap

A real RAG pipeline should cap its spend. Add this to the graph builder:

```java
BudgetPolicy budget = BudgetPolicy.hierarchical(
        BudgetLimits.builder()
                .perRun(0.50)    // $0.50 max per question
                .perCall(0.10)   // refuse any call > $0.10
                .build(),
        estimator, meter);

AgentGraph.builder()
        // ...
        .budgetPolicy(budget)
        .build();
```

See [the budget policy docs](https://datallmhub.github.io/agentflow4j/resilience/#6-budget-policy-cost-gate) for how to wire `CostEstimator` and `CostMeter`.

---

## Files

- [`RagAgentDemo.java`](src/main/java/io/github/datallmhub/cookbook/rag/RagAgentDemo.java) — the whole demo, ~150 lines
- [`pom.xml`](pom.xml) — Maven module config

---

## Next steps

- Recipe [02 — Support ticket triage](../02-support-ticket-triage/) — adds conditional routing + tool policies
- Recipe [03 — Web research agent](../03-web-research-agent/) — real HTTP search + streaming tokens
- Recipe [05 — Batch document processor](../05-batch-document-processor/) — checkpoint + resume after crash
