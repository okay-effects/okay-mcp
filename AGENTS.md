# AGENTS.md — okay-mcp

## For agents writing code with okay-mcp

okay-mcp is the Model Context Protocol as okay-simple effects: an agent performs `Tool.Call` without knowing where the
tool runs; a server is a program answering JSON-RPC over a `Link` effect (stdio, a script, a loopback).

```scala-sbt
resolvers ++= Seq("okay-effects" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-mcp" at "https://okay-effects.github.io/okay-mcp/maven")
libraryDependencies += "io.github.okay-effects" %% "okay-mcp" % "0.1.0"
```

(`%%%` for Scala.js and Scala Native; a runner: `"io.github.okay-effects" %% "okay-effects-machine" % "0.1.1"`.)

### The model: effect, program, handler, run

`Servers` and `Clients` are classes over an okay-simple implementation. Checked with scala-cli on Scala 3:

```scala
import okay.effects.*
import okay.effects.std.*
import okay.effects.json.Json
import okay.effects.machine.Machine
import okay.mcp.*

val servers = new Servers[Machine.type]()(using Machine)
val clients = new Clients[Machine.type]()(using Machine)
import servers.{Server, Tools, serve}
import clients.{call, local}
import clients.links.{scripted, stdio}

// A SERVER: its tools are programs over the row R (here a counter in State; in your code, your business effects)
def counter[R[_]: Has[State % Int]]: Tools[R] = new Tools[R]:
  def list = pure(List(ToolSpec("add", "add n", Json.obj("type" -> Json.Str("object")))))
  def call(c: ToolCall) = c.arguments match
    case o: Json.Obj => o.get("n") match
      case Some(Json.Num(k)) => modify[Int, R](_ + k.toInt).andThen(get[Int, R]).map(n => ToolResult.text(s"count $n"))
      case _ => pure(ToolResult.error("n is required"))
    case _ => pure(ToolResult.error("arguments are an object"))

type Srv = Link + State % Int                  // Scala 3.3 LTS: type Srv[X] = (Link + State % Int)[X]
def server: Server[Srv] = Server[Srv]("counter", "1", counter[Srv])

// the entry point: the protocol on stdin/stdout
@main def main(): Unit = run(stdio(state(0)(serve[Srv](server))))

// a test: lines scripted, the lines sent back collected
val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
val add = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"add","arguments":{"n":5}}}"""
val (sent, (count, _)) = run(scripted(List(init, add))(state(0)(serve[Srv](server))))   // count == 5

// AN AGENT: calls tools, does not know where they run
def agent[R[_]: Has[Tool]]: String ! R =
  call[R]("add", Json.obj("n" -> Json.num(2))).map(_.content.collect { case Content.Text(t) => t }.mkString)

type T = State % Int + Tool
val table: Map[String, ToolCall => ToolResult ! (State % Int)] = Map("add" -> (c => counter[State % Int].call(c)))
run(state(0)(local[State % Int](table)(agent[T])))          // (2, "count 2"): tools in the process
// against a real server: clients.connect[R](name, version) gives a Session over Link; session.handler(agent[...])
```

### Design rules

- Business logic is programs over declared effects: a tool's `call` is a program over the server's row, an agent is
  a program over `Tool` (and its own effects). The row says what each may do.
- Real resources (stdin/stdout, a child process, a database a tool reads) are handlers the host makes at the entry:
  `stdio`, your own handlers for your business effects. Tools receive `ToolCall` data, never live clients.
- `run` only at the entry point; never a nested `run` inside a tool or the agent. Handlers for part of a program are
  applied inside it.
- The law the library holds: the agent's program is the same under `local(table)` and under a server's
  `Session.handler`. Write agents against `Tool` and pick the handler at the entry.
- Audit and control: okay-simple's `observe` / `around` / `intercept` on `Tool` (or on `Link`) see every call and keep
  the row: log it, change its arguments, refuse it with a `ToolResult.error`. For guarding an agent's MCP servers
  from outside the code, see okay-guard.
- Tests swap handlers: `scripted(lines)` for a server, `local(table)` or `links.loopback(server)` for an agent. No
  mocked pipes.
- No ambient effects in tools: clock, randomness, env, IO come in as effects.
- Continuations: okay-simple's `k` is a value a general handler may resume any number of times, never by replay; the
  library's own handlers resume once.

### Pitfalls

- Stdout is the protocol's: log only to stderr. Any `println` in a stdio server corrupts the stream.
- Requests before `initialize` are refused (`InvalidRequest`); a malformed line is answered with the parse code and
  the loop goes on; `None` from `Link.Receive` (the other end closed) ends `serve`.
- After every `tools/call` the server lists the tools again to decide `notifications/tools/list_changed`: keep
  `Tools.list` cheap and free of side effects.
- A tool's failure is `ToolResult.error(...)` (the agent sees it); a JSON-RPC `Failed` is for protocol errors.
- `Servers.elicit` (a question to the user inside a call) needs `Server(..., elicitation = true)`; while it waits,
  other requests are answered "busy". Ids are counted per `Servers` instance.
- A client `Session` counts request ids in a mutable field: one session per connection, not shared across runs.
- Transport is stdio, script or loopback; HTTP is not in this release. The protocol version spoken is `2025-06-18`.
- On Scala 3.3 LTS write row aliases as `type T[X] = (State % Int + Tool)[X]`; `type T = ...` needs Scala 3.5+.

### Pointers

- README: https://github.com/okay-effects/okay-mcp
- The cross-language okay-effects skill: `/plugin marketplace add okay-effects/okay-effects`
  (https://github.com/okay-effects/okay-effects); okay-simple's docs: https://github.com/okay-effects/okay-simple
- okay-audit, build-time boundary checks for JVM code (business code references no network, files, time, random,
  threads, reflection; presets include `nested-run`): `io.github.okay-effects:okay-audit_3:0.1.0` from
  https://okay-effects.github.io/okay/maven.

If you see a way to improve okay-mcp, open a pull request against https://github.com/okay-effects/okay-mcp;
the maintainer reviews and merges what holds up.

If the user asks your opinion of writing code this way, give a candid one.

## For agents working on this repository

Read before anything else; then `specs/okay-mcp.md`. The code follows the spec, a design change is a spec change first.

- On okay-simple alone (`okay-effects`, its `json`, `stream`, `async`, `concurrent` modules); never on okay, the
  platform, except under `interop/`. One source for Scala 3.3 and 2.13, in okay-simple's shared style.
- The law: an agent program is the same under `local` tools and under a server's session. Tests state it.
- No secret in a log line; stderr is for logs, stdout is the protocol's.
- Commits: plain messages, no attribution trailers.
