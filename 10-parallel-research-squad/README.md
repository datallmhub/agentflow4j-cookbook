# Recipe 10: Parallel research squad (fan-out, join and a gated branch in Java)

> **Three researchers work the same brief at once, and the writer runs when all three are in.** The fan-out is graph structure, the budget is shared, and the approval gate on the paid source pauses only its own branch. A Java/Spring AI example you can clone and run in 30 seconds, no API key.

---

## What you'll build

```
                    ┌──────────┐
                ┌──▶│  market  │──┐
  ┌──────┐      │   └──────────┘  │   ┌──────────┐
  │ plan │──────┼──▶│   tech   │──┼──▶│  report  │
  └──────┘      │   └──────────┘  │   └──────────┘
                │   ┌──────────┐  │
                └──▶│   paid   │──┘   ← ApprovalGate
                    └──────────┘
```

Three direct edges out of `plan` are three parallel branches; three direct edges into `report` are one join. `report` runs **once**, after the last branch.

---

## Run it

```bash
mvn -pl 10-parallel-research-squad exec:java
```

Works without Ollama: the researchers return stub findings, and the parallel mechanics are identical. Start Ollama with `llama3.2:3b` for real findings.

Expected output:

```
── first attempt: the paid branch waits for approval
  interrupted: approval.required:paid.analyst
  run log: plan, market, tech

── the analyst is approved: only that branch runs
REPORT
  market: ...
  tech:   ...
  paid:   ...
  run log: paid.analyst, report

llm calls: 3 | researchers spent $1.50 of the $2.00 run cap
```

Read the two run logs: the free researchers ran and were checkpointed while the paid one waited, the resume ran only the approved branch, and `report` waited for it instead of writing an incomplete report.

---

## Key concepts demonstrated

| Concept | Where in the code |
|---|---|
| **Fan-out** | three `addEdge("plan", ...)` direct edges |
| **Join** | three `addEdge(..., "report")` edges; the writer runs once, when the branches are in |
| **Bounded concurrency** | `.maxConcurrency(3)` |
| **No state collision** | each researcher writes its own `StateKey`; two branches writing the same key with different values would raise `StateConflictException` |
| **Shared budget** | one `BudgetPolicy` with `perRun(2.00)` for the whole fan-out, charging researchers only |
| **Partial approval** | `ApprovalGate.requireFor("paid.analyst")` pauses that branch alone; `resume(runId, ResumeOptions.ofApproval(...))` does not replay the others |
| **Audit** | `graph.runLog(runId)` shows what ran in parallel, and `NODE_SKIPPED` what was not replayed |

---

## Why this matters in production

Running three researchers in sequence is three times the latency for no reason: they do not depend on each other. Doing it by hand with an executor, though, is where governance usually dies, because the budget counters, the approval pause and the audit trail all have to survive concurrency.

- **Latency without losing control.** The branches run at once, and the run-level budget still caps the total: an expensive fan-out cannot escape the ceiling by parallelising.
- **A gate should stop one branch, not the workflow.** The free research is done and checkpointed while a human decides on the paid call, so the wait costs nothing.
- **A join must wait.** A report written before the last branch is worse than a slow report; the runtime holds the join until every incoming branch is in.

See the framework docs: [Parallel branches](https://datallmhub.github.io/agentflow4j/parallel/).

---

## Next steps

- Add `onRejection("paid.analyst", "plan")` to rerun the plan when the analyst is refused; nodes that already ran are skipped, not repeated.
- Swap `InMemoryCheckpointStore` for the JDBC store so a fan-out paused for approval survives a restart.
- Give each researcher a `RetryPolicy`: a flaky branch retries without holding the others.

---

## Files

- [`ParallelResearchDemo.java`](src/main/java/io/github/datallmhub/cookbook/parallel/ParallelResearchDemo.java)
- [`pom.xml`](pom.xml)
