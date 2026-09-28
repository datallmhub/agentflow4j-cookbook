# Recipe 07: Governed MCP agent (MCP tools under a ToolPolicy in Java)

> **Give an agent the tools of an MCP server without giving up control.** A support agent uses an order system exposed over MCP; a `ToolPolicy` refuses risky refunds before they reach the server, every call is audited, and the model is told when it was refused. A Java/Spring AI example you can clone and run in 30 seconds, no API key.

---

## What you'll build

```
                        AgentGraph "support-desk"
 ┌────────┐    ┌──────────────────────────────────────────────┐
 │ ticket │ ─▶ │ ExecutorAgent "support"                      │
 └────────┘    │   ToolPolicy: refunds > 100 EUR ─▶ refused   │
               │   audit: ToolCallRecord per call             │
               └───────────────────┬──────────────────────────┘
                                   │ MCP over stdio
                          ┌────────▼─────────┐
                          │ OrdersMcpServer  │  lookup_order
                          │ (child process)  │  refund_order
                          └──────────────────┘
```

AF4J does not reimplement MCP. Spring AI's `SyncMcpToolCallbackProvider` turns the server's tools into `ToolCallback`s; handing that provider to `ExecutorAgent.toolProviders(...)` puts every MCP call under the graph's governance.

---

## Run it

```bash
mvn -pl 07-mcp-governed-agent exec:java
```

The demo starts the MCP server itself as a child Java process: nothing to install. Without Ollama, a deterministic stub model drives the same tool calls through Spring AI's tool-calling machinery. Start Ollama with a tool-calling model such as `llama3.2:3b` for live answers.

Expected output (STUB mode):

```
[mcp] server tools [lookup_order, refund_order], exposed to the agent as orders_*

ticket: Please refund 20 EUR on order A-1001, one headphone is scratched.
  [audit] orders_lookup_order    OK      A-1001: 2x wireless headphones, 89.00 EUR, delivered 3 days ago
  [audit] orders_refund_order    OK      refunded 20.0 EUR on order A-1001

ticket: Refund order A-1002 in full, I changed my mind.
  [audit] orders_lookup_order    OK      A-1002: standing desk, 640.00 EUR, delivered yesterday
  [audit] orders_refund_order    REFUSED ToolPolicyViolation: tool policy denied call to 'orders_refund_order': refunds above 100.0 EUR need a human approval
```

The refused refund never reaches the MCP server. The model receives the denial reason as the tool result, so it can explain the refusal to the customer instead of failing.

---

## Key concepts demonstrated

| Concept | Where in the code |
|---|---|
| **MCP tools on an agent** | `ExecutorAgent.builder().toolProviders(new SyncMcpToolCallbackProvider(mcp))` |
| **Tool policy on MCP calls** | `ToolPolicy.when((tool, args) -> !REFUND_TOOL.equals(tool) \|\| amount(args) <= 100, ...)` |
| **Prefixed tool names** | Spring AI exposes `refund_order` from client `orders` as `orders_refund_order`; policies use that name (`McpToolUtils.prefixedToolName`) |
| **Audit trail** | Every call, allowed or refused, is a `ToolCallRecord` in `AgentResult.toolCalls()` |
| **Lifecycle hook** | `AgentListener.onToolCall` prints each call as it is recorded |
| **Real MCP transport** | `StdioClientTransport` launching `OrdersMcpServer` built with the MCP Java SDK |

---

## Why this matters in production

MCP makes it trivial to plug a new capability into an agent, which is exactly the risk: a server can expose a `refund_order` or `delete_customer` tool next to a harmless `lookup_order`. Governance has to sit between the model and the server:

- **Register MCP tools on the agent, not on the `ChatClient`.** Tools added with `ChatClient.builder(model).defaultToolCallbacks(...)` bypass the `ToolPolicy` and the audit entirely.
- **Refuse, don't crash.** A refused call comes back to the model as a tool result, so the conversation continues and the customer gets an answer.
- **Tools can change at runtime.** The provider is queried on every run, so a tool added by the server is picked up without a restart, and is governed like the others.

See the framework docs: [MCP tools](https://datallmhub.github.io/agentflow4j/mcp/) and [Tool policy](https://datallmhub.github.io/agentflow4j/tool-policy/).

---

## Next steps

- With `spring-ai-starter-mcp-client`, inject the auto-configured `ToolCallbackProvider` bean instead of building the client by hand.
- Put an `ApprovalGate` on a dedicated refund node to route large refunds to a human instead of refusing them.
- Record the audit with a `RunLogStore` to keep it across restarts.

---

## Files

- [`McpGovernedAgentDemo.java`](src/main/java/io/github/datallmhub/cookbook/mcp/McpGovernedAgentDemo.java): the agent, the policy and the stub model
- [`OrdersMcpServer.java`](src/main/java/io/github/datallmhub/cookbook/mcp/OrdersMcpServer.java): the MCP server
- [`logback.xml`](src/main/resources/logback.xml): keeps logs off stdout, which carries the MCP protocol

The MCP server and client are built with the MCP Java SDK 1.0 API: `McpJsonDefaults.getMapper()` resolves the JSON mapper through the service loader, and tools are declared with `SyncToolSpecification.builder()`.
- [`pom.xml`](pom.xml)
