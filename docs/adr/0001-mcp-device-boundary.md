---
status: accepted
---

# Keep LLM execution outside the MCP module

Verity serves both autonomous CLI runs and interactive device control. The MCP module shares core and device capabilities with the CLI but does not depend on the agent module: the external MCP caller owns LLM reasoning and credentials. This keeps device tools usable independently of Verity's agent configuration, at the cost of requiring the caller to coordinate navigation and assertions; device preflight belongs in MCP, while provider, model, and credential preflight belongs in the CLI run path.

Recorded from the July 2026 preflight and configuration designs. See the [preflight spec](../specs/preflight-checks.md) and [module dependency graph](../architecture.md#module-dependency-graph). The CLI command that starts MCP still uses the shared project-config resolver, as described in the [configuration spec](../specs/project-configuration.md).
