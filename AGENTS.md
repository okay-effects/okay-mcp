# AGENTS.md — okay-mcp

Read before anything else; then `specs/okay-mcp.md`. The code follows the spec, a design change is a spec change first.

- On okay-simple alone (`okay-effects`, its `json`, `stream`, `async`, `concurrent` modules); never on okay, the
  platform, except under `interop/`. One source for Scala 3.3 and 2.13, in okay-simple's shared style.
- The law: an agent program is the same under `local` tools and under a server's session. Tests state it.
- No secret in a log line; stderr is for logs, stdout is the protocol's.
- Commits: plain messages, no attribution trailers.
