package okay.mcp

import okay.effects.{Runner, Sum, Void}
import okay.effects.json.Json
import okay.effects.json.Json.{Str, num, obj}
import okay.effects.machine.Machine
import okay.effects.free.Free

class RpcSuite extends munit.FunSuite {
  test("encode and decode, every kind, the id as it is") {
    val ms = List(Rpc.Request(num(1), "tools/list", Json.Obj.empty), Rpc.Request(Str("a-b"), "x", obj("k" -> num(2))), Rpc.Notify("notifications/initialized", Json.Obj.empty),
      Rpc.Answer(num(1), obj("tools" -> Json.arr())), Rpc.Failed(num(2), -32601, "unknown", Some(Str("d"))), Rpc.Failed(Json.Null, -32700, "parse"))
    ms.foreach(m => assertEquals(Rpc.decode(Rpc.encode(m)), Right(m)))
  }
  test("a damaged line is a Failed with the parse code; a non-object too") {
    assertEquals(Rpc.decode("{nope").left.map(_.code), Left(Rpc.Code.Parse))
    assertEquals(Rpc.decode("[1]").left.map(_.code), Left(Rpc.Code.InvalidRequest))
    assertEquals(Rpc.decode("""{"id": 1}""").left.map(_.code), Left(Rpc.Code.InvalidRequest))
  }
}

abstract class McpChecks(val fx0: Runner, name: String) extends munit.FunSuite {
  val servers: Servers[fx0.type] = new Servers[fx0.type]()(fx0)
  val clients: Clients[fx0.type] = new Clients[fx0.type]()(fx0)
  import fx0._
  import fx0.std._
  import servers.{Server, Session, Tools, dispatch, elicit, serve}
  import clients.links.scripted

  type L[X] = Sum[Void, Link, X]
  type SL[X] = Sum[L, ({ type S[x] = State[Int, x] })#S, X]

  /** a counter: `add(n)` adds, `reset` zeroes; the tool list grows a `reset` once the count is above zero */
  def counting[R[_]](implicit s: In[({ type S[x] = State[Int, x] })#S, R]): Tools[R] = new Tools[R] {
    def list: List[ToolSpec] ! R = get[Int, R].map(n => ToolSpec("add", "add n", obj("type" -> Str("object"), "properties" -> obj("n" -> obj("type" -> Str("integer"))), "required" -> Json.arr(Str("n")))) ::
      (if (n > 0) List(ToolSpec("reset", "back to zero", obj("type" -> Str("object")))) else Nil))
    def call(c: ToolCall): ToolResult ! R = c.name match {
      case "add" => c.arguments match {
        case o: Json.Obj => o.get("n") match {
          case Some(Json.Num(k)) => modify[Int, R](_ + k.toInt).flatMap(_ => get[Int, R]).map(n => ToolResult(List(Content.Text(s"count $n")), Some(obj("count" -> num(n)))))
          case _ => pure[ToolResult, R](ToolResult.error("n is required, a number"))
        }
        case _ => pure[ToolResult, R](ToolResult.error("arguments are an object"))
      }
      case "reset" => put[Int, R](0).map(_ => ToolResult.text("count 0"))
      case other => pure[ToolResult, R](ToolResult.error(s"no tool $other"))
    }
  }
  def server[R[_]](implicit s: In[({ type S[x] = State[Int, x] })#S, R]): Server[R] = Server[R]("counter", "1", counting[R], instructions = Some("count things"))

  def line(m: Rpc): String = Rpc.encode(m)
  val init = line(Rpc.Request(num(1), "initialize", obj("protocolVersion" -> Str("2025-06-18"), "capabilities" -> Json.Obj.empty, "clientInfo" -> obj("name" -> Str("t")))))
  def rpc(l: String): Rpc = Rpc.decode(l).fold(f => f, m => m)
  def decodeAll(lines: List[String]): List[Rpc] = lines.map(rpc)

  test(s"$name: the server over a scripted link — initialize, list, call, list_changed, errors, the end") {
    val script = List(init, line(Rpc.Notify("notifications/initialized", Json.Obj.empty)),
      line(Rpc.Request(num(2), "tools/list", Json.Obj.empty)),
      line(Rpc.Request(num(3), "tools/call", obj("name" -> Str("add"), "arguments" -> obj("n" -> num(5))))),
      line(Rpc.Request(num(4), "tools/call", obj("name" -> Str("add"), "arguments" -> obj("n" -> Str("x"))))),
      line(Rpc.Request(num(5), "nope", Json.Obj.empty)),
      line(Rpc.Request(num(6), "ping", Json.Obj.empty)))
    val (sent, (count, _)) = fx0.run(scripted(script)(state(0)(serve[SL](server[SL]))))
    val replies = decodeAll(sent)
    assertEquals(count, 5)
    replies.head match { case Rpc.Answer(id, r: Json.Obj) => assertEquals(id, num(1)); assertEquals(r.get("protocolVersion"), Some(Str("2025-06-18"))); assert(r.get("capabilities").exists(_.toString.contains("listChanged"))); case other => fail(s"$other") }
    replies(1) match { case Rpc.Answer(_, r: Json.Obj) => assertEquals(r.get("tools").collect { case Json.Arr(ts) => ts.size }, Some(1)); case other => fail(s"$other") }
    replies(2) match { case Rpc.Answer(_, r: Json.Obj) => assertEquals(r.get("structuredContent"), Some(obj("count" -> num(5)))); case other => fail(s"$other") }
    assertEquals(replies(3), Rpc.Notify("notifications/tools/list_changed", Json.Obj.empty))   // the count above zero: `reset` appeared
    replies(4) match { case Rpc.Answer(_, r: Json.Obj) => assertEquals(r.get("isError"), Some(Json.Bool(true))); case other => fail(s"$other") }
    assertEquals(replies(5), Rpc.Failed(num(5), Rpc.Code.MethodNotFound, "unknown method: nope"))
    assertEquals(replies(6), Rpc.Answer(num(6), Json.Obj.empty))
    assertEquals(replies.size, 7)
  }
  test(s"$name: a request before initialize is refused; a damaged line answered with the parse code") {
    val (sent, _) = fx0.run(scripted(List(line(Rpc.Request(num(1), "tools/list", Json.Obj.empty)), "{broken"))(state(0)(serve[SL](server[SL]))))
    assertEquals(decodeAll(sent).map(_.asInstanceOf[Rpc.Failed].code), List(Rpc.Code.InvalidRequest, Rpc.Code.Parse))
  }
  test(s"$name: the client over a loopback to the server, and the law: the agent's program is the same under local tools") {
    import clients.{call, connect, local}
    type T[X] = Sum[Void, Tool, X]
    /** the agent: adds twice, resets, adds once; its result the texts */
    def agent[R[_]](implicit t: In[Tool, R]): List[String] ! R =
      call[R]("add", obj("n" -> num(2))).flatMap { a =>
        call[R]("add", obj("n" -> num(3))).flatMap { b =>
          call[R]("reset").flatMap { c =>
            call[R]("add", obj("n" -> num(1))).map { d => List(a, b, c, d).flatMap(_.content.collect { case Content.Text(t) => t }) }
          }
        }
      }
    // local: a table of programs over State
    type TS[X] = Sum[({ type S[x] = State[Int, x] })#S, Tool, X]
    val table: Map[String, ToolCall => ToolResult ! ({ type S[x] = State[Int, x] })#S] = Map(
      "add" -> (c => counting[({ type S[x] = State[Int, x] })#S].call(c)), "reset" -> (c => counting[({ type S[x] = State[Int, x] })#S].call(c)))
    val (_, underLocal) = fx0.run(state(0)(local[({ type S[x] = State[Int, x] })#S](table)(agent[TS])))
    // the session: the server behind a loopback, its state its own
    var session = servers.fresh; var count = 0
    val loop = clients.links.loopback[Void] { l =>
      val (n, (s2, replies)) = fx0.run(state(count)(dispatch[({ type S[x] = State[Int, x] })#S](server, session, rpc(l))))
      session = s2; count = n; pure[List[String], Void](replies.map(Rpc.encode))
    }
    type TL[X] = Sum[L, Tool, X]
    val underSession = fx0.run(loop(connect[L]("agent", "1").flatMap(s => s.handler(agent[TL]))))
    assertEquals(underSession, underLocal)
    assertEquals(underLocal, List("count 2", "count 5", "count 0", "count 1"))
    val tools = fx0.run(loop(connect[L]("agent", "1").flatMap(_.tools)))
    assertEquals(tools.map(_.name), List("add", "reset"))
  }
  test(s"$name: elicitation — a question to the user from inside a call, the answer awaited, other requests told busy") {
    def asking[R[_]](implicit l: In[Link, R]): Tools[R] = new Tools[R] {
      def list: List[ToolSpec] ! R = pure[List[ToolSpec], R](List(ToolSpec("secret", "needs consent", obj("type" -> Str("object")))))
      def call(c: ToolCall): ToolResult ! R = elicit[R]("Show the secret?", obj("type" -> Str("object"), "properties" -> obj("yes" -> obj("type" -> Str("boolean"))))).map {
        case Right(o: Json.Obj) if o.get("yes") == Some(Json.Bool(true)) => ToolResult.text("the secret")
        case Right(_) => ToolResult.error("not allowed")
        case Left(d) => ToolResult.error(s"declined: ${d.action}")
      }
    }
    val srv = Server[L]("s", "1", asking[L], elicitation = true)
    val (sent, _) = fx0.run(scripted(List(init,
      line(Rpc.Request(num(2), "tools/call", obj("name" -> Str("secret")))),
      line(Rpc.Request(num(3), "ping", Json.Obj.empty)),                                         // while the question is pending: busy
      s"""{"jsonrpc":"2.0","id":"elicit-${servers.hashCode % 1 + 1}","result":{"action":"accept","content":{"yes":true}}}""",
      line(Rpc.Request(num(4), "tools/call", obj("name" -> Str("secret")))),
      """{"jsonrpc":"2.0","id":"elicit-2","result":{"action":"decline"}}"""))(serve[L](srv)))
    val replies = sent.map(rpc)
    replies(1) match { case Rpc.Request(_, "elicitation/create", p: Json.Obj) => assertEquals(p.get("message"), Some(Str("Show the secret?"))); case other => fail(s"$other") }
    assertEquals(replies(2).asInstanceOf[Rpc.Failed].id, num(3))
    replies(3) match { case Rpc.Answer(id, r: Json.Obj) => assertEquals(id, num(2)); assert(r.toString.contains("the secret"), r.toString); case other => fail(s"$other") }
    replies(5) match { case Rpc.Answer(id, r: Json.Obj) => assertEquals(id, num(4)); assert(r.toString.contains("declined: decline"), r.toString); case other => fail(s"$other") }
  }
}
class McpOnMachine extends McpChecks(Machine, "machine")
class McpOnFree extends McpChecks(Free, "free")
