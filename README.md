# AgentFlow4J Cookbook — multi-agent LLM workflow examples in Java

[![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/java-17%2B-orange.svg)](https://adoptium.net/)
[![AgentFlow4J 0.7.0](https://img.shields.io/badge/agentflow4j-0.7.0-green.svg)](https://github.com/datallmhub/agentflow4j)
[![Spring AI 1.0](https://img.shields.io/badge/spring--ai-1.0-brightgreen.svg)](https://docs.spring.io/spring-ai/reference/)

**Runnable examples for building multi-agent LLM workflows in Java** — RAG, support-ticket triage, web research, Slack bots, batch document processing, and cost-aware routing — with [AgentFlow4J](https://github.com/datallmhub/agentflow4j) and [Spring AI](https://docs.spring.io/spring-ai/reference/).

If you want to orchestrate **governed, production-ready multi-agent workflows on the JVM** — with budget caps, tool policies, human approval gates, and checkpoint-based recovery — this is a copy-paste starting point. Every recipe is a self-contained Maven module: clone, pick a recipe, run it. Local-first — each one runs against [Ollama](https://ollama.com/) with **zero API keys and zero cost**, and swaps to OpenAI, Mistral, or Anthropic by changing one Spring AI starter dependency.

---

## Why this cookbook exists

Java has a vibrant LLM ecosystem ([LangChain4j](https://github.com/langchain4j/langchain4j), [Spring AI](https://docs.spring.io/spring-ai/reference/), [Embabel](https://github.com/embabel/embabel-agent), [AgentFlow4J](https://github.com/datallmhub/agentflow4j)) but most tutorials stop at **single-agent chatbots**. Real production AI systems need more:

- **multiple specialised agents** coordinating on one task (triage → specialist → review)
- **durable state** that survives restarts and crashes
- **governance** — budget caps, tool allowlists, human-in-the-loop approval gates
- **observable execution traces** for debugging non-deterministic LLM behaviour

This cookbook shows those production patterns end-to-end in **idiomatic Java + Spring**, with code you can lift straight into your own project. No Python, no microservice sidecar, no YAML DSL — just typed Java on the JVM you already run.

---

## Recipes

| # | Recipe | What you'll learn |
|---|---|---|
| 1 | [**RAG agent**](01-rag-agent/) | Retrieval-augmented generation with a retriever agent + answerer agent + cited sources, all governed by a budget cap |
| 2 | [**Support ticket triage**](02-support-ticket-triage/) | Classify customer tickets, route to specialist agents, enforce a content policy, draft a reply — with `ToolPolicy` denylists |
| 3 | [**Web research agent**](03-web-research-agent/) | Real-time research over Hacker News with a parse → search → classify → synthesize graph, streaming tokens via `Flux<AgentEvent>` |
| 4 | [**Slack bot**](04-slack-bot/) | Multi-agent Slack assistant — listens to mentions, runs a planning + executor graph, posts a threaded reply |
| 5 | [**Batch document processor**](05-batch-document-processor/) | Process N documents through the same agent graph with checkpointing — resume from the last successful doc after a crash |
| 6 | [**Cost-aware routing**](06-cost-aware-routing/) | Degrade a squad from a premium model to a cheaper fallback as the budget depletes with `RoutingStrategy.budgetAware`, and retry only what's worth retrying via a reason-aware `RetryPolicy` (transient vs permanent vs over-budget) |

---

## Requirements

- **Java 17+** ([Adoptium Temurin](https://adoptium.net/) recommended)
- **Maven 3.9+** (or use the wrapper)
- [**Ollama**](https://ollama.com/) running locally (optional — recipes fall back to stubs when Ollama isn't reachable)

> Every recipe **also runs in stub mode without Ollama** so you can verify the wiring before adding an LLM.

---

## Quick start

```bash
git clone https://github.com/datallmhub/agentflow4j-cookbook.git
cd agentflow4j-cookbook

# Build everything (uses JitPack to fetch AgentFlow4J)
mvn -DskipTests install

# Run a single recipe (no LLM required — falls back to stubs)
mvn -pl 01-rag-agent exec:java
```

---

## Local LLM setup (Ollama)

The cookbook talks to [Ollama](https://ollama.com/) by default — free, offline, no API key.

```bash
# 1. Install Ollama (macOS / Linux)
brew install ollama                # or: curl -fsSL https://ollama.com/install.sh | sh

# 2. Start the daemon (defaults to http://localhost:11434)
ollama serve &

# 3. Pull a small, fast chat model (~2 GB)
ollama pull llama3.2:3b
```

That's it — every recipe auto-detects Ollama on `localhost:11434` and runs in LIVE mode.

### Configuration

Two env vars, both optional:

| Variable | Default | Notes |
|---|---|---|
| `OLLAMA_HOST`  | `http://localhost:11434` | Use `http://localhost:11435` (etc.) if port 11434 is taken (e.g. LM Studio) |
| `OLLAMA_MODEL` | `llama3.2:3b`            | Any model you've pulled — `ollama list` to check |

```bash
# Example: bigger model
ollama pull llama3.1:8b
OLLAMA_MODEL=llama3.1:8b mvn -pl 01-rag-agent exec:java

# Example: Ollama on a non-default port (when 11434 is in use)
OLLAMA_HOST=http://localhost:11435 mvn -pl 01-rag-agent exec:java
```

A fast TCP probe at startup decides between LIVE and STUB mode — no hang if Ollama isn't running.

### Switching to a cloud provider (OpenAI / Mistral / Anthropic)

The recipes are intentionally Ollama-coupled to keep the cookbook clone-and-run. To use a hosted provider, swap the Spring AI starter dependency in the recipe's `pom.xml` (e.g. `spring-ai-starter-model-openai`) and replace the `OllamaChatModel` builder block with the equivalent for your provider. The rest of the recipe (graph, state, policies) is unchanged.

---

## Project structure

```
agentflow4j-cookbook/
├── pom.xml                       # Parent POM, BOM imports, shared deps
├── 01-rag-agent/                 # Each recipe is a standalone Maven module
│   ├── pom.xml
│   ├── README.md                 #   ← detailed walkthrough
│   └── src/main/java/...
├── 02-support-ticket-triage/
├── 03-web-research-agent/
├── 04-slack-bot/
├── 05-batch-document-processor/
└── 06-cost-aware-routing/
```

---

## Related resources

- **[AgentFlow4J](https://github.com/datallmhub/agentflow4j)** — the multi-agent orchestration framework these recipes use
- **[AgentFlow4J docs](https://datallmhub.github.io/agentflow4j/)** — concepts, governance, resilience, observability
- **[Two API levels](https://datallmhub.github.io/agentflow4j/two-api-levels/)** — when to use the high-level squad API vs the low-level graph API
- **[Stop your agent burning $1000 overnight](https://datallmhub.github.io/agentflow4j/tutorials/stop-your-agent-burning-money/)** — tutorial on the four governance gates
- **[Spring AI reference](https://docs.spring.io/spring-ai/reference/)** — the Spring abstraction over LLM providers

---

## Frequently asked

**How does this compare to LangGraph or LangChain?**
AgentFlow4J is not a port of LangGraph. It's a governed orchestration runtime — the difference is `BudgetPolicy`, `ToolPolicy`, `ApprovalGate`, and `FailureClassifier` built into the execution model, not bolted on top. If you're on Spring and need production governance over your agent workflows, these recipes show what that looks like end to end.

**Do I need an OpenAI API key?**
No. Every recipe runs against a local [Ollama](https://ollama.com/) model out of the box. Cloud providers (OpenAI, Mistral, Anthropic) are a one-dependency swap.

**Can I use these in production?**
The patterns (graph composition, typed state, governance gates, checkpoint-based resume) are production-grade. The sample data is illustrative — replace the in-memory stores with your real vector store / database.

---

## Topics

`java` · `llm` · `ai-agents` · `multi-agent` · `agent-orchestration` · `spring-ai` · `spring-boot` · `rag` · `llm-governance` · `langchain4j` · `ollama` · `generative-ai` · `agentflow4j`

---

## Contributing

Have a use case that isn't covered? Open an issue describing the scenario — we add recipes that solve concrete production problems, not toy demos.

---

## License

Apache License 2.0 — same as AgentFlow4J. See [LICENSE](LICENSE).
