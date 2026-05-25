# Recipe 04 — Slack bot with multi-agent planner-executor in Java

> **Build a Slack assistant that plans its actions, executes them against a workspace, and posts a threaded reply** — using AgentFlow4J + Spring AI. Runs in-process with a simulated workspace so you can iterate without a real Slack app.

---

## What you'll build

```
┌──────┐     ┌─────────┐     ┌──────┐
│ plan │ ──▶ │ execute │ ──▶ │ post │
└──────┘     └─────────┘     └──────┘
   LLM        deterministic   thread
  planner       workspace      reply
```

A **plan** agent breaks the user request into 1-3 actions (`PIN_LIST`, `MEMBER_COUNT`). An **execute** agent runs them against the workspace. A **post** agent assembles the final threaded reply.

---

## Why simulated Slack?

A real Slack app needs:
- a Slack workspace + admin permissions
- OAuth installation flow
- a public HTTPS callback for events
- bot tokens stored as secrets

That's appropriate for a real product, but it would dwarf the *agent logic* the cookbook is trying to teach.

So this recipe ships an **in-memory `SlackWorkspace`** with the same method surface you'd hit on the real Slack API. To go live:

1. Add the `slack-api-client` dependency
2. Replace `SlackWorkspace#execute(...)` with calls to `methods.chatPostMessage(...)`, `pinsList(...)`, `conversationsInfo(...)`
3. Wrap the bot in a controller listening for Slack Events API webhooks

The agent graph stays unchanged.

---

## Run it

```bash
mvn -pl 04-slack-bot exec:java

# with a custom mention
mvn -pl 04-slack-bot exec:java -Dexec.args="@bot count members in #general"
```

Without Ollama the recipe uses keyword-based step planning. With Ollama the planner is an LLM constrained to a tight grammar (`PIN_LIST(#channel)`, `MEMBER_COUNT(#channel)`).

---

## Key concepts demonstrated

| Concept | Where in the code |
|---|---|
| **Plan-then-execute pattern** | The plan node outputs a structured action list, executed downstream — never the LLM directly side-effects |
| **Constrained LLM output** | The system prompt locks the LLM to one action per line, preventing prose hallucinations |
| **Replaceable infrastructure** | `SlackWorkspace` is a plain class with the same surface as `slack-api-client` — swap in production |
| **Graph-level idempotency** | Re-running `plan → execute → post` against the same context produces the same Slack output |

---

## Going to production

| Concern | How to address |
|---|---|
| **Auth** | Use Slack OAuth + `slack-api-client`. Store the bot token in Spring's `${SLACK_BOT_TOKEN}`. |
| **Webhooks** | Add a `@RestController` for `/slack/events` and validate the signing secret on every request. |
| **Rate limits** | Wrap `SlackWorkspace` with a `Resilience4j` rate limiter — Slack allows ~1 msg/sec per channel. |
| **Idempotency** | Use `graph.invoke(ctx, runId)` with `runId = event_id` from the Slack event so retries don't double-post. |
| **Budget cap** | Add a `BudgetPolicy` so a runaway loop doesn't burn your LLM quota answering one nuisance user. |

---

## Files

- [`SlackBotDemo.java`](src/main/java/io/github/datallmhub/cookbook/slack/SlackBotDemo.java)
- [`pom.xml`](pom.xml)
