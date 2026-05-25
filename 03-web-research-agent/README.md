# Recipe 03 — Web research agent in Java

> **Multi-agent Java workflow that searches the public web (Hacker News) and synthesises a digest** using AgentFlow4J + Spring AI. Real HTTP, real ranking, optional LLM summarisation.

---

## What you'll build

```
┌────────┐     ┌──────┐     ┌─────────────┐
│ search │ ──▶ │ rank │ ──▶ │ synthesize  │
└────────┘     └──────┘     └─────────────┘
   HN          top-K by         LLM digest
 Algolia       points         (or fallback)
```

A **search** agent hits the [Hacker News Algolia API](https://hn.algolia.com/api), a **rank** agent keeps the top-5 stories by points, and a **synthesize** agent asks the LLM for a one-paragraph digest with source links.

---

## Run it

```bash
mvn -pl 03-web-research-agent exec:java

# with a custom topic
mvn -pl 03-web-research-agent exec:java -Dexec.args="rust async runtime"
```

Works without an LLM (falls back to a bullet-list digest). Start Ollama with `llama3.2:3b` for a true natural-language digest.

---

## Key concepts demonstrated

| Concept | Where in the code |
|---|---|
| **Real-world HTTP call from an agent** | `hackerNewsSearch(query)` uses `java.net.http.HttpClient` — no extra framework needed |
| **Pipeline state** | Each node mutates `HITS` (a `StateKey<List<Hit>>`) which the next node reads |
| **Graceful degradation** | If HN is unreachable or the LLM is offline, the graph still completes with the best result it can produce |
| **Provider-agnostic chat** | The `synthesize` node uses Spring AI's `ChatClient` — swap Ollama for OpenAI / Mistral / Anthropic in one dependency |

---

## Why a graph instead of a single LLM call?

You could ask an LLM "search HN for X and summarise" — but the LLM can't actually call HN. With a graph:

- **The search step is deterministic** — no hallucinated URLs, no fake points counts
- **The rank step is testable** — pure function, easy to unit-test
- **The LLM only does what LLMs are good at** — natural language summarisation over a small, trusted input

This is the right division of labour between deterministic code and LLM agents.

---

## Extending it

- **Add a web-fetch node** that follows the top URL and pulls the article body for deeper summarisation
- **Add a `BudgetPolicy`** to cap how many synth calls a single query may trigger
- **Stream tokens** via `Flux<AgentEvent>` for a live "ChatGPT-style" output — see the [HnRadarDemo in the main repo](https://github.com/datallmhub/agentflow4j/blob/main/agentflow4j-samples/src/main/java/io/github/datallmhub/agentflow4j/samples/HnRadarDemo.java)

---

## Files

- [`ResearchDemo.java`](src/main/java/io/github/datallmhub/cookbook/research/ResearchDemo.java)
- [`pom.xml`](pom.xml)
