# Recipe 11: Governed OpenHands workflow (a coding agent behind approval, budget and checkpoints)

> **Hand a ticket to OpenHands without handing over control.** A human approves before the paid coding run, the run is checkpointed so nothing blocks while the agent works, the spend is capped, and the pull request comes back into the workflow. A Java/Spring example you can clone and run in 30 seconds, no OpenHands account.

---

## What you'll build

```
  ticket ─▶ triage ─▶ [ApprovalGate] ─▶ code (OpenHands) ─▶ announce
                            │                  │
                    human approves     interrupt + checkpoint,
                     the spend          resumed until finished
```

`OpenHandsAgent` is a node like any other: AF4J does not reimplement the coding agent, it governs it.

---

## Run it

```bash
mvn -pl 11-governed-openhands exec:java
```

A local stub answers the OpenHands V1 endpoints, so the recipe runs with no account. To run it for real:

```bash
export OPENHANDS_API_KEY=...          # from OpenHands Cloud, Settings > API Keys
export OPENHANDS_REPOSITORY=you/repo
mvn -pl 11-governed-openhands exec:java
```

Expected output (STUB mode):

```
── the ticket arrives
  [approval] code: node 'code' requires human approval
  interrupted: approval.required:code

── a human approves the spend
  [checkpoint] next=[code] conversation=conv-1
  interrupted: openhands.running:conv-1

── a scheduler resumes the run while OpenHands works
  poll 1: openhands.running:conv-1
  poll 2: done

PR #1042 on branch openhands/fix-1042 (cost $1.05)
```

Note the second checkpoint: the conversation id is in the state before the run pauses. That is what makes every later resume poll the same conversation instead of starting a second, paid one.

---

## Key concepts demonstrated

| Concept | Where in the code |
|---|---|
| **Approval before spending** | `ApprovalGate.requireFor("code")`, resumed with `ResumeOptions.ofApproval("code")` |
| **Async coding node** | `OpenHandsAgent` in `Mode.ASYNC`: it interrupts with `openhands.running` instead of holding a thread |
| **Durable wait** | `checkpointStore(...)`: the run survives a restart between two polls |
| **Idempotent resume** | `OpenHandsKeys.CONVERSATION_ID` in state; the loop resumes ten times and starts one conversation |
| **Cost cap** | `.maxCost(5.00)`: above it, the sandbox is paused and the run interrupts with `budget.exceeded` |
| **Result back in the workflow** | `OpenHandsKeys.PULL_REQUEST`, `BRANCH` and `COST` feed the `announce` node |
| **Audit** | the run log shows the approval, every poll and the final transition |

---

## Why this matters in production

An autonomous coding agent is the easiest way to turn a ticket into a pull request, and the easiest way to spend money on a task nobody sanctioned. The governance has to sit outside the agent:

- **Approve before paying, not after.** The gate fires before the conversation is created, so a rejected ticket costs nothing.
- **Do not hold a thread for an hour.** A coding task runs for minutes to hours and OpenHands caps a session at 12 hours. The async node turns that wait into a checkpoint, which also survives a deploy.
- **Resume must be idempotent.** A scheduler that polls every minute must never start a second conversation; the conversation id in the state guarantees that, including after a failed poll or a rejected approval.
- **Cap the spend at the node.** OpenHands reports its accumulated cost, so the node stops the sandbox instead of discovering the bill later.

See the framework docs: [OpenHands](https://datallmhub.github.io/agentflow4j/openhands/) and [Approval gate](https://datallmhub.github.io/agentflow4j/approval-gate/).

---

## Next steps

- Replace the polling loop with your scheduler (`@Scheduled`, a queue worker, or a webhook from your CI once the PR opens).
- Swap `InMemoryCheckpointStore` for the JDBC store so a run in flight survives a restart.
- Add `onRejection("code", "triage")` to send a refused ticket back for a better task description; nodes that already ran are not repeated.
- Review the pull request with a second agent, as in recipe 09.

---

## Files

- [`GovernedOpenHandsDemo.java`](src/main/java/io/github/datallmhub/cookbook/openhands/GovernedOpenHandsDemo.java): the workflow
- [`LocalOpenHandsStub.java`](src/main/java/io/github/datallmhub/cookbook/openhands/LocalOpenHandsStub.java): the stand-in server, so the recipe runs with no account
- [`pom.xml`](pom.xml)
