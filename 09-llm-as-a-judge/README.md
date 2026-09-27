# Recipe 09: LLM as a judge (a structured verdict that routes the graph in Java)

> **Score an answer with a second agent, and act on the score.** The judge returns a typed record, not prose: a good answer is sent, a weak one goes back to the writer with the reasons, a risky one goes to a human. A Java/Spring AI example you can clone and run in 30 seconds, no API key.

---

## What you'll build

```
                        ┌──────── score >= 7 ────────▶ send
  question ─▶ write ─▶ judge ───── risky ────────────▶ escalate (human)
                 ▲          └───── rounds = 2 ───────▶ escalate (human)
                 └────────── else: reasons ───────────┘
```

The judge is an `ExecutorAgent` with an `outputKey`, so Spring AI maps the model's JSON onto a record and the graph routes on its fields:

```java
public record Verdict(int score, boolean risky, List<String> reasons) {}

ExecutorAgent judge = ExecutorAgent.builder()
        .name("judge")
        .chatClient(chat)
        .systemPrompt(JUDGE_PROMPT)
        .outputKey(VERDICT)     // the verdict lands in typed state
        .build();

.addEdge(Edge.conditional("judge", ctx -> ctx.get(VERDICT).risky(), "escalate"))
.addEdge(Edge.conditional("judge", ctx -> ctx.get(VERDICT).score() >= 7, "send"))
```

---

## Run it

```bash
mvn -pl 09-llm-as-a-judge exec:java
```

Works without Ollama: a stub judge scores on the same signals a real one would. Start Ollama with `llama3.2:3b` for a real judge.

Expected output:

```
── q-1: My parcel is two days late, where is it?
  [write #1] It is late.
  [judge   ] {"score":5,"risky":false,"reasons":["too short","no tracking information"]}
  [write #2] Your parcel is delayed by the carrier and is expected within 48 hours; ...
  [judge   ] {"score":9,"risky":false,"reasons":["accurate","actionable"]}
  SENT: ...
  run log: write -> judge -> write -> judge -> send
  judging cost $0.20 of the $0.50 run cap

── q-2: I want my money back for the broken lamp.
  [write #1] We will refund you in full today, guaranteed.
  [judge   ] {"score":4,"risky":true,"reasons":["promises a refund","uses 'guaranteed'"]}
  TO A HUMAN: promises a refund; uses 'guaranteed' | draft: ...
  run log: write -> judge -> escalate
```

---

## Key concepts demonstrated

| Concept | Where in the code |
|---|---|
| **Structured verdict** | `ExecutorAgent.outputKey(VERDICT)`: the model's JSON becomes a `Verdict` record in typed state |
| **Routing on a field** | three `Edge.conditional` out of `judge`, in priority order: risky, then score, then rounds |
| **Bounded review** | a round counter that escalates instead of looping, with `maxIterations` as a backstop |
| **The judge has a price** | a `BudgetPolicy` that charges the `judge` node only, built per run |
| **Audit** | the run log shows every round and where the answer ended up |

---

## Why this matters in production

An LLM judge is the usual answer to "how do we know the output is good", and the usual way to double a bill without improving anything. Two rules keep it useful:

- **Judge only what code cannot check.** Length, format, banned claims and schema belong in a deterministic reviewer (recipe 08), which is free and testable. Keep the model for tone, faithfulness and risk.
- **A score nobody acts on is decoration.** The value is in the routing: send, revise, or escalate. That is a graph decision, which is why the verdict has to be typed state rather than prose.
- **Escalate, never loop forever.** A judge that keeps refusing is a signal to involve a human, and the round counter makes that explicit.

Related: recipe 08 shows the deterministic reviewer, and [Typed state](https://datallmhub.github.io/agentflow4j/state/) explains `StateKey`.

---

## Next steps

- Store the verdicts and compare them against human labels before you trust the judge.
- Judge with a cheaper model than the writer: a `ChatClient` per node is all it takes.
- Add an `ApprovalGate` on `send` for the highest-risk categories, so a human signs off even on a passing answer.

---

## Files

- [`LlmAsAJudgeDemo.java`](src/main/java/io/github/datallmhub/cookbook/judge/LlmAsAJudgeDemo.java)
- [`pom.xml`](pom.xml)
