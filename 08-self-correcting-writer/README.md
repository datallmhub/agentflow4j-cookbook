# Recipe 08: Self-correcting writer (a bounded revise-until-valid loop in Java)

> **Let an LLM fix its own output, without letting it loop forever.** A writer agent drafts product copy, a deterministic reviewer checks it against house rules and sends it back with the list of issues, and after three drafts the run escalates to a human. A Java/Spring AI example you can clone and run in 30 seconds, no API key.

---

## What you'll build

```
            ┌───────────────── issues, attempts < 3 ─────────────────┐
            ▼                                                        │
 brief ─▶ write ─▶ review ──── no issues ────▶ publish               │
                     │ └───────────────────────────────────────────────┘
                     └──────── attempts = 3 ──▶ escalate (human editor)
```

The loop is ordinary graph structure: two `Edge.conditional` edges out of `review` and a direct fallback edge back to `write`. The writer reads the reviewer's issues from typed state and revises its previous draft.

---

## Run it

```bash
mvn -pl 08-self-correcting-writer exec:java
```

Works without Ollama: the stub writer gets the first draft wrong on purpose, then applies the feedback. Start Ollama with `llama3.2:3b` for live drafts.

Expected output (STUB mode):

```
── brief-1: Noise-cancelling wireless headphones, 30h battery, foldable, for commuters.
  [write  #1] Title: The best in the world noise-cancelling wireless headphones for every commuter
  [review  ] the title has 77 characters, the limit is 60; there must be exactly 3 bullet points, found 2; remove the claim 'guaranteed'; remove the claim 'best in the world'
  [write  #2] Title: Quiet commute headphones, 30h battery
  [review  ] accepted
PUBLISHED
  run log: write -> review -> write -> review -> publish

── brief-2: A miracle anti-ageing cream that is guaranteed to erase wrinkles.
  ...
NEEDS A HUMAN EDITOR after 3 drafts. Open issues: remove the claim 'guaranteed'; remove the claim 'miracle'
  run log: write -> review -> write -> review -> write -> review -> escalate
```

The second brief asks for claims the house rules forbid: no amount of rewriting fixes it, so the loop stops at three drafts and hands over.

---

## Key concepts demonstrated

| Concept | Where in the code |
|---|---|
| **Feedback loop** | `Edge.conditional("review", no issues, "publish")`, then `addEdge("review", "write")` as the fallback |
| **Bounded retries** | `Edge.conditional("review", attempts >= 3, "escalate")` plus `maxIterations(7)` as a hard backstop |
| **Feedback through typed state** | the reviewer writes `ISSUES`, the writer reads `DRAFT` and `ISSUES` to revise |
| **Deterministic reviewer** | `review(...)` is plain Java: title length, bullet count, banned claims |
| **Audit** | `RunOptions.ofRunId(...)` + `InMemoryRunLogStore`; `graph.runLog(runId)` lists every attempt |

---

## Why this matters in production

Self-correction is one of the most effective ways to get usable output from a model, and one of the easiest ways to burn money: a loop that asks the model to "try again" until it is happy has no natural end.

- **Put the acceptance criteria in code.** A reviewer written in Java is free, instant, testable and explainable; a second LLM acting as the judge is none of those. Use an LLM judge only for what code cannot check (see recipe 09).
- **Always bound the loop.** An explicit attempt limit with an escalation path turns "the model cannot do it" into a normal outcome, not a timeout.
- **Keep the history.** The run log shows how many drafts each item needed: a direct signal of prompt quality and cost.

Spring AI 1.1 adds recursive advisors that can retry inside a single `ChatClient` call. A graph loop remains the right tool when each attempt should be visible, bounded and audited as a step of the workflow.

---

## Next steps

- Add a `BudgetPolicy` to cap the spend of the loop in currency, not only in attempts.
- Replace the `escalate` node with an `ApprovalGate` so an editor can approve the last draft as is.
- Unit-test `review(...)` directly: it is a plain function from context to result.

---

## Files

- [`SelfCorrectingWriterDemo.java`](src/main/java/io/github/datallmhub/cookbook/selfcorrect/SelfCorrectingWriterDemo.java)
- [`pom.xml`](pom.xml)
