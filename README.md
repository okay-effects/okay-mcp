# okay-mcp — the Model Context Protocol as effects

An agent program performs `Tool.Call` and does not know whether the tool is a local function, a server over stdio, or
a table in a test: the handler decides. A server is a program that answers requests over a `Link` effect: stdin and
stdout, an HTTP stream later, or a scripted pair in a test. JSON-RPC as data, JSON parsed by effects. Built on
[okay-simple](https://github.com/okay-effects/okay-simple) alone, for Scala 3 and 2.13; the transport
[okay-face](https://github.com/okay-effects/okay-face)'s MCP face stands on.

Status: `core` in — JSON-RPC as data, `Link` with stdio, scripted and loopback handlers, the server (initialize, tools,
resources, `list_changed`, ping, elicitation from inside a call), the client (`Tool`, `local`, a `Session`), the law
held by a test on the machine and on Free, both compilers. [okay-face](https://github.com/okay-effects/okay-face)'s MCP
face and [verity](https://github.com/okay-effects/verity)'s agent face stand on it. HTTP and the domain projection by
`Describe` are next. [specs/okay-mcp.md](specs/okay-mcp.md).
