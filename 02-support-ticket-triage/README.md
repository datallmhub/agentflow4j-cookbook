# Recipe 02 — Support ticket triage (multi-agent classification in Java)

> **Route incoming customer support tickets through a graph of specialist agents** with conditional edges and a content-policy gate. A Java/Spring AI multi-agent example you can clone and run in 30 seconds.

---

## What you'll build

```
                       ┌────────┐
                  ┌───▶│ refund │
                  │    └────────┘
   ┌────────┐    │    ┌────────┐     ┌────────┐     ┌───────┐
   │ triage │ ──┼───▶│billing │────▶│ policy │────▶│ reply │
   └────────┘    │    └────────┘     └────────┘     └───────┘
                  │    ┌──────────┐
                  └───▶│technical │
                       └──────────┘
```

A **triage** agent classifies the ticket (refund / billing / technical), a **conditional edge** routes it to the matching specialist drafter, a **policy** agent inspects the draft for sensitive content (e.g. passwords), and a **reply** agent assembles the final response — or escalates to a human if the policy fails.

---

## Run it

```bash
mvn -pl 02-support-ticket-triage exec:java

# with a custom ticket body
mvn -pl 02-support-ticket-triage exec:java -Dexec.args="My invoice for January looks wrong"
```

Works without Ollama (stub triage uses keyword heuristics). Start Ollama with `llama3.2:3b` for live LLM classification.

---

## Key concepts demonstrated

| Concept | Where in the code |
|---|---|
| **Conditional edges** | `Edge.conditional("triage", ctx -> ctx.get(CATEGORY) == Category.REFUND, "refund")` |
| **Fan-in pattern** | All three specialists converge into `policy` then `reply` |
| **Content-policy gate** | The `policy` agent inspects the draft and sets `POLICY_PASSED` |
| **Escalation path** | If policy fails, `reply` switches to "escalated to a human" instead of sending the draft |

---

## Why this matters in production

A naive "classify and reply" prompt has no observability and no safety net. Splitting into a graph gives you:

- **Audit trail** — the run log shows exactly which category was assigned and why
- **Independent improvement** — you can swap the triage classifier (e.g. fine-tune a smaller model for it) without touching the specialists
- **Policy enforcement** — the `policy` node is the single chokepoint where you'd plug PII detection, profanity filters, or a hard denylist

For a deeper governance setup with `BudgetPolicy` + `ToolPolicy` + `ApprovalGate`, see the [Stop your agent burning $1000 tutorial](https://datallmhub.github.io/agentflow4j/tutorials/stop-your-agent-burning-money/).

---

## Next steps

- Add a `BudgetPolicy` to cap spend per ticket
- Replace the keyword `policy` check with a real PII detector (e.g. AWS Comprehend, Presidio)
- Persist runs with a `CheckpointStore` so a crash mid-ticket doesn't lose work — see [Recipe 05](../05-batch-document-processor/)

---

## Files

- [`TriageDemo.java`](src/main/java/io/github/datallmhub/cookbook/triage/TriageDemo.java)
- [`pom.xml`](pom.xml)
