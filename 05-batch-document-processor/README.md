# Recipe 05 — Batch document processing with crash recovery (Java)

> **Process N documents through the same multi-agent graph with resume-after-crash semantics.** A Java/Spring AI cookbook recipe showing the pattern every batch LLM pipeline needs but most demos skip.

---

## What you'll build

```
   for each doc:
   ┌─────────┐     ┌─────┐
   │ analyze │ ──▶ │ tag │     ← graph (per-doc)
   └─────────┘     └─────┘
       │              │
       └──→ summary, tags written to state
            │
            └──→ progress.txt (last-processed index)
```

For each document the loop runs a small `analyze → tag` graph, then persists the index to disk. On restart the loop **skips** documents already done.

---

## Run it

```bash
mvn -pl 05-batch-document-processor exec:java
```

Then simulate a crash:

```bash
# In one terminal:
mvn -pl 05-batch-document-processor exec:java
# After 3-4 documents: Ctrl-C

# Re-run — it resumes from where it stopped
mvn -pl 05-batch-document-processor exec:java
```

The marker file lives at `05-batch-document-processor/target/batch-progress.txt`. Delete it to re-run from scratch.

---

## Why this matters

Almost every real LLM batch workload — embedding 100k product descriptions, summarising a year of support tickets, tagging an article archive — eventually crashes. Without resume semantics, you start over and re-pay for the LLM calls that already succeeded.

The pattern shown here is dead simple:

1. **Assign each unit of work a stable `runId`** — here `"doc-" + index`
2. **Mark progress only after a unit succeeds** — atomic file write
3. **Read the marker on startup** to skip done work

That's it. No queue, no orchestrator, no Airflow.

---

## Two complementary checkpointing layers

| Scope | What it protects |
|---|---|
| **Batch loop** (this recipe) | Documents already fully processed are not re-processed after a crash |
| **Intra-graph** (`CheckpointStore`) | Pauses inside one document's graph survive crashes — e.g. an `ApprovalGate` waiting for a human |

For the intra-graph layer, attach a `CheckpointStore` to the `AgentGraph` and pass the same `runId` to `invoke(ctx, runId)`:

```java
AgentGraph graph = AgentGraph.builder()
        .addNode(...)
        .checkpointStore(new InMemoryCheckpointStore())  // or JDBC / Redis
        .build();

graph.invoke(AgentContext.of(doc), "doc-" + i);
```

See the [Checkpointing docs](https://datallmhub.github.io/agentflow4j/) for `JdbcCheckpointStore` and human-approval flows.

---

## Key concepts demonstrated

| Concept | Where in the code |
|---|---|
| **Stable run ids** | `graph.invoke(ctx, "doc-" + i)` — same id ⇒ same checkpoint key, idempotent re-runs |
| **Atomic progress marker** | `writeResumeIndex()` writes after every success; on restart `readResumeIndex()` reads it |
| **Per-doc state isolation** | Each `AgentContext` is fresh — no leakage between documents |
| **Resume semantics from a flat file** | No external state store needed; suitable for dev, testing, simple cron jobs |

---

## Going to production

| Concern | How to address |
|---|---|
| **Concurrent workers** | Replace the flat file with a row in `processed_docs (doc_id, processed_at)` and use `SELECT … FOR UPDATE SKIP LOCKED` |
| **Partial-failure visibility** | Attach a `RunLogStore` — query failed `runId`s for triage |
| **Retry strategy** | Add `RetryPolicy.exponentialBackoff(3)` at the agent level — transient failures self-heal |
| **Cost cap** | Add a per-run `BudgetPolicy` — a single rogue document can't burn the whole batch's budget |

---

## Files

- [`BatchProcessorDemo.java`](src/main/java/io/github/datallmhub/cookbook/batch/BatchProcessorDemo.java)
- [`pom.xml`](pom.xml)
