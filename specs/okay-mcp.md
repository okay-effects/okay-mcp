# okay-mcp — the Model Context Protocol as effects, on okay-simple

Status: specification, 2026-10-20. No code yet. Decided with the operator: MCP is a transport, not a face, and it is
not only for faces — any described effect is a set of tools — so it is its own repository, on okay-simple alone, as
okay-face is. okay-face's `face-mcp` becomes a thin bridge onto this.

## Overview

MCP is JSON-RPC 2.0 over a byte stream: a client asks a server what it offers — tools, resources, prompts — and calls
them; a server may ask the client's user for structured input (elicitation) or the client's model for a completion
(sampling). Both ends are programs here:

- An AGENT PROGRAM performs the effect `Tool` — `Call(name, arguments): Json` — and does not know whether the tool is a
  local function, an MCP server over stdio, or a table of canned answers in a test: the handler decides. That is the
  thesis of the family, stated in okay's specs/mcp.md: "an MCP server is another Tool handler, and its JSON-RPC framing
  is our parser plus Schema". Here the parser is okay-simple's `json` module and the schema okay-face's `Shape` (or any
  JSON Schema value).
- A SERVER is a program that answers a stream of requests: given `Tools` — the specs and a program per call — and
  optionally resources, prompts and the ability to elicit, `serve(link)(tools)` runs the protocol. Its transport is
  the effect `Link` — `Send(line)`, `Receive: Option[line]` — handled by the process's stdin/stdout, by an HTTP stream
  later, or by a scripted pair in a test.

No dependency but okay-simple: `okay-effects`, `okay-effects-json` (JSON parsed by effects, from a text or a stream),
`okay-effects-stream`, `okay-effects-async` for Scala.js and non-blocking transports, `okay-effects-concurrent` for a
client and a server in one process. Both compilers, every platform okay-simple builds for. okay (the platform) has its
own okay-mcp on okay-core; this one is its sibling on the released floor, and a bridge between them is `interop/`, later.

## Interface

### JSON-RPC 2.0 as data (`okay.mcp.rpc`)

```scala
sealed trait Rpc
object Rpc {
  final case class Request(id: Json, method: String, params: Json) extends Rpc
  final case class Notify(method: String, params: Json) extends Rpc
  final case class Answer(id: Json, result: Json) extends Rpc
  final case class Failed(id: Json, code: Int, message: String, data: Option[Json]) extends Rpc
  def decode(line: String): Rpc            // a damaged line is a `Failed` with code -32700, never a throw
  def encode(m: Rpc): String               // one line, no newline inside (`Json.print`)
  object Code { val Parse = -32700; val InvalidRequest = -32600; val MethodNotFound = -32601; val InvalidParams = -32602; val Internal = -32603 }
}
```

### The link (`okay.mcp.link`)

```scala
sealed trait Link[X]
object Link {
  final case class Send[X](line: String, answer: Unit => X) extends Link[X]
  final case class Receive[X](answer: Option[String] => X) extends Link[X]      // `None`: the other end closed
}
def stdio: handler of Link on the process's standard streams (JVM, Native), blocking; stderr untouched, for logs
def scripted(lines: List[String]): handler of Link answering `Receive` from the list and collecting `Send`s — the test's
def channel: a pair of handlers joined by okay-effects-concurrent's channel: a client and a server in one program
```

### The server (`okay.mcp.server`)

```scala
final case class ToolSpec(name: String, description: String, inputSchema: Json, outputSchema: Option[Json] = None)
final case class ToolCall(name: String, arguments: Json)
final case class ToolResult(content: List[Content], structured: Option[Json] = None, isError: Boolean = false)
sealed trait Content; object Content { final case class Text(s: String) extends Content; /* Image, Resource later */ }

trait Tools[R[_]] {
  def list: List[ToolSpec] ! R                         // may change between calls: the server sends `tools/list_changed` when it does
  def call(c: ToolCall): ToolResult ! R                // unknown name, bad arguments: an `isError` result or `InvalidParams`
}
trait Resources[R[_]] { def list: List[ResourceSpec] ! R; def read(uri: String): List[ResourceContent] ! R }
trait Prompts[R[_]] { ... }                            // later

final case class Server[R[_]](name: String, version: String, tools: Tools[R], resources: Option[Resources[R]] = None,
                              instructions: Option[String] = None)
def serve[R[_]](server: Server[R]): Unit ! (R + Link)  // the loop: `initialize` → `initialized` → requests until the link closes
def elicit[P, R[_]](message: String, shape: Json)(implicit Link): Either[Declined, Json] ! R   // `elicitation/create`, when the client declared it
```

Protocol version `2025-06-18`; capabilities declared from what the `Server` has (`tools.listChanged: true` always);
`ping`; unknown methods `MethodNotFound`; a request before `initialize` is `InvalidRequest`. Notifications the server
sends: `notifications/tools/list_changed`, `notifications/resources/updated`, `notifications/resources/list_changed`.

### The client (`okay.mcp.client`)

```scala
sealed trait Tool[X]
object Tool { final case class Call[X](c: ToolCall, answer: ToolResult => X) extends Tool[X] }
def call[R[_]](name: String, arguments: Json): ToolResult ! R

final class Session[R[_]] {                           // after `connect(link, name, version)`: `initialize` done
  def tools: List[ToolSpec] ! R                        // `tools/list`, the cursor followed
  def call(c: ToolCall): ToolResult ! R                // `tools/call`
  def resources: ...; def read(uri): ...
  def handler: handler of Tool over this session        // an agent program's `Tool` calls go to the server
}
def connect[R[_]](name: String, version: String): Session[R] ! (R + Link)
def local(table: Map[String, ToolCall => ToolResult ! R]): handler of Tool    // tools in the process; a test's
```

A client program under `local` and under `Session.handler` is the same program: the law this repository is for.

### Transports

- `stdio`: one JSON-RPC message per line, UTF-8; what Claude Code, the inspector and most clients speak today.
- `http` (later, its own module): the streamable HTTP transport, `POST` for messages and SSE for the server's stream,
  on okay-effects-async; a server for Scala.js on Node as well.

## Tests

- `Rpc`: decode what encode writes; a damaged line a `Failed(-32700)`; ids of any JSON kind kept as they are.
- Server, scripted link: `initialize` answers the declared capabilities; `tools/list`; `tools/call` with good and bad
  arguments; an unknown method; a request before `initialize`; the link closing ends `serve`; `list_changed` sent when
  `Tools.list` changes between calls (a `Tools` over `State`).
- Client, scripted link: `connect`, `tools`, `call`; a server's error is a `ToolResult(isError)` or a raised error.
- Client and server in one process over `channel`: the agent program's result equal under `local` and under the session.
- Both compilers, on the machine and on `Free`; stdio on the JVM against the MCP inspector by hand, recorded in docs.

## Order of work

1. `rpc` and `link` with `scripted`; tests.
2. `server`: `serve` with tools; tests; a `stdio` run against Claude Code or the inspector.
3. `client`: `connect`, `Session`, `local`; the law test over `channel`.
4. Resources, elicitation (okay-face's `Consent` needs it); `face-mcp` in okay-face rewritten as a bridge.
5. `http`.

## Build

sbt as okay-face's: cross 3.3.8 / 2.13.18, JVM always, JS and Native when asked; `okay-effects-json` needs okay-simple
0.1.1 — until it is released, `publishLocal` from okay-simple's master. Modules `rpc`, `link`, `server`, `client`,
`stdio`; artifacts `okay-mcp-*`; `io.github.okay-effects`, Apache-2.0.
