# okay-mcp — the Model Context Protocol as effects

An agent program performs `Tool.Call` and does not know whether the tool is a local function, a server over stdio, or
a table in a test: the handler decides. A server is a program that answers requests over a `Link` effect: stdin and
stdout, an HTTP stream later, or a scripted pair in a test. JSON-RPC as data, JSON parsed by effects. Built on
[okay-simple](https://github.com/okay-effects/okay-simple) alone, for Scala 3 and 2.13; the transport
[okay-face](https://github.com/okay-effects/okay-face)'s MCP face stands on.

Status: specification, [specs/okay-mcp.md](specs/okay-mcp.md).
