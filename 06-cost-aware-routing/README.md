# Recipe 06 — Cost-aware routing (budget-aware fallback + reason-aware retries in Java)

> **Keep a multi-agent LLM workload from quietly burning money.** Route to a premium model while there's budget left, degrade to a cheaper fallback when it runs low, and retry only the failures worth retrying. A Java/Spring AI example you can clone and run in 30 seconds — no API key.

---

## What you'll build

```
                  budget-aware router
   ┌────────┐    remaining ≥ $2 ?    ┌──────────────────┐
   │ ticket │ ──────────┬──────────▶ │ premium  ($1.50) │
   └────────┘           │            └──────────────────┘
                        │ else       ┌──────────────────┐
                        └──────────▶ │ fallback ($0.25) │
                                     └──────────────────┘
```

**Scene A — budget-aware routing.** A `CoordinatorAgent` sends each ticket to a *premium* executor while the run budget allows, then **degrades gracefully** to a cheaper *fallback* once the remaining budget drops below a threshold. The decision is deterministic and free — it reads the live `BudgetPolicy` counters, with no extra LLM call to "classify complexity".

**Scene B — reason-aware retries.** A `RetryPolicy` carrying a `FailureClassifier` retries a transient blip (honouring a `Retry-After` hint), gives up immediately on a permanent error, and surfaces budget exhaustion as an interrupt instead of hammering the provider.

---

## Run it

```bash
mvn -pl 06-cost-aware-routing exec:java
```

Works without Ollama — the routing and retry mechanics are deterministic, so the demo is identical in STUB mode. Start Ollama with `llama3.2:3b` for live answers.

Expected output (abridged): the first three tickets are handled by **premium**, then — once less than `$2.00` of the `$5.00` run budget remains — the squad switches to **fallback**:

```
budget left $5.00 | Summarise our refund policy in one line.
  [premium  gpt-4-class] handled
budget left $3.50 | Draft a reply ...
  [premium  gpt-4-class] handled
budget left $2.00 | What is the capital of France?
  [premium  gpt-4-class] handled
budget left $0.50 | Explain our SLA ...
  [fallback mini-class ] handled
```

---

## Key concepts demonstrated

| Concept | Where in the code |
|---|---|
| **Budget-aware routing** | `RoutingStrategy.budgetAware(budget, Scope.RUN, 2.00, "premium", "fallback")` |
| **Shared live budget** | the router and the executors share one `BudgetPolicy` instance, so the router sees real spend |
| **Cost metering** | `CostMeter` charges `$1.50` per premium call, `$0.25` per fallback call |
| **Reason-aware retry** | `RetryPolicy.exponential(3, …).withClassifier(domain.orElse(FailureClassifier.defaults()))` |
| **Failure categories** | `TRANSIENT` (retry, honour `Retry-After`) · `PERMANENT` (stop) · `OVER_BUDGET` (interrupt) |

---

## Why this matters in production

A single agent retrying a paid API on every blip — or always reaching for your most expensive model — is how an overnight run turns into a surprise invoice. Two cheap levers fix most of it:

- **Spend the expensive model where it counts.** Budget-aware routing keeps quality high while budget is healthy and automatically trades down instead of either stopping dead or overspending. It's the one cost-aware lever that's both *deterministic* and *provably cheaper* — classifying complexity with an LLM would itself cost a call.
- **Retry on reason, not reflex.** A `429` should wait for its `Retry-After`; a `400` should never be retried; a quota error should page a human, not loop. The `FailureClassifier` encodes that once and applies it on every attempt.

See the framework docs: [Resilience & error handling](https://datallmhub.github.io/agentflow4j/resilience/).

---

## Next steps

- Wire the **same `BudgetPolicy`** into an `AgentGraph` via `.budgetPolicy(budget)` so node execution also gates and records cost automatically.
- Swap the per-call `CostMeter` for `CostMeter.totalTokens().scaledBy(dollarsPerToken)` to bill real token usage.
- Add domain rules to the classifier for your provider's SDK exceptions, then `.orElse(FailureClassifier.defaults())` to keep the built-in handling.

---

## Files

- [`CostAwareRoutingDemo.java`](src/main/java/io/github/datallmhub/cookbook/routing/CostAwareRoutingDemo.java)
- [`pom.xml`](pom.xml)
