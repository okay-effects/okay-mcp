package okay.mcp

import okay.effects.Effects
import okay.effects.json.Json

/** THE AGENT'S EFFECT: a tool called. Where it runs is the handler's — a table in the process (`local`), an MCP server
 * over a link (`Session.handler`) — and the agent's program is the same under either: the law */
sealed trait Tool[X]
object Tool { final case class Call[X](c: ToolCall, answer: ToolResult => X) extends Tool[X] }

class Clients[F <: Effects](implicit implementation: F) {
  val fx: F = implementation
  import fx._
  val links: Links[fx.type] = new Links[fx.type]()(fx)
  import links.{receiveRpc, sendRpc}

  def call[R[_]](name: String, arguments: Json = Json.Obj.empty)(implicit in: In[Tool, R]): ToolResult ! R =
    perform[Tool].apply[R, ToolResult](Tool.Call(ToolCall(name, arguments), r => r))

  /** tools in the process: a table of programs of the row outside */
  def local[Rest[_]](table: Map[String, ToolCall => ToolResult ! Rest]): Local[Rest] = new Local[Rest](table)
  final class Local[Rest[_]](table: Map[String, ToolCall => ToolResult ! Rest]) {
    def apply[R[_], A](p: A ! R)(implicit sp: Split[Tool, R, Rest]): A ! Rest =
      handle[Tool, R, Rest, A, A](p, new Handler[Tool, Rest, A, A] {
        def ret(a: A) = pure(a)
        def op[X](op: Tool[X], k: X => A ! Rest) = op match {
          case Tool.Call(c, answer) => table.get(c.name).fold(pure[ToolResult, Rest](ToolResult.error(s"no tool ${c.name}")))(_(c)).flatMap(r => k(answer(r)))
        }
      })
  }

  /** a session with a server over the link: `initialize` done; ids counted; an answer awaited past notifications */
  final class Session[R[_]](val serverInfo: Json)(implicit in: In[Link, R]) {
    private var next = 1
    private def request(method: String, params: Json): Either[Rpc.Failed, Json] ! R = {
      val id = Json.num(next); next += 1
      sendRpc[R](Rpc.Request(id, method, params)).flatMap(_ => await(id))
    }
    private def await(id: Json): Either[Rpc.Failed, Json] ! R = receiveRpc[R].flatMap {
      case None => pure[Either[Rpc.Failed, Json], R](Left(Rpc.Failed(id, Rpc.Code.Internal, "the link closed")))
      case Some(Right(Rpc.Answer(i, result))) if i == id => pure[Either[Rpc.Failed, Json], R](Right(result))
      case Some(Right(f @ Rpc.Failed(i, _, _, _))) if i == id => pure[Either[Rpc.Failed, Json], R](Left(f))
      case Some(Right(Rpc.Request(other, "ping", _))) => sendRpc[R](Rpc.Answer(other, Json.Obj.empty)).flatMap(_ => await(id))
      case Some(_) => await(id)     // a notification, someone else's answer, a malformed line
    }
    def tools: List[ToolSpec] ! R = request("tools/list", Json.Obj.empty).map {
      case Right(o: Json.Obj) => o.get("tools").collect { case Json.Arr(ts) => ts.toList.flatMap(spec) }.getOrElse(Nil)
      case _ => Nil
    }
    def call(c: ToolCall): ToolResult ! R = request("tools/call", Json.obj("name" -> Json.Str(c.name), "arguments" -> c.arguments)).map {
      case Right(o: Json.Obj) => ToolResult(
        o.get("content").collect { case Json.Arr(cs) => cs.toList.flatMap { case x: Json.Obj => x.get("text").collect { case Json.Str(t) => Content.Text(t) }; case _ => None } }.getOrElse(Nil),
        o.get("structuredContent"), o.get("isError").contains(Json.Bool(true)))
      case Right(other) => ToolResult.error(s"not a result: ${Json.print(other)}")
      case Left(f) => ToolResult.error(f.message)
    }
    /** `Tool` handled by this session: the agent's calls go to the server */
    def handler: SessionHandler = new SessionHandler
    final class SessionHandler {
      def apply[R0[_], A](p: A ! R0)(implicit sp: Split[Tool, R0, R]): A ! R =
        handle[Tool, R0, R, A, A](p, new Handler[Tool, R, A, A] {
          def ret(a: A) = pure(a)
          def op[X](op: Tool[X], k: X => A ! R) = op match { case Tool.Call(c, answer) => call(c).flatMap(r => k(answer(r))) }
        })
    }
    private def spec(j: Json): Option[ToolSpec] = j match {
      case o: Json.Obj => for { n <- o.get("name").collect { case Json.Str(s) => s }; d = o.get("description").collect { case Json.Str(s) => s }.getOrElse("") }
        yield ToolSpec(n, d, o.get("inputSchema").getOrElse(Json.Obj.empty), o.get("outputSchema"))
      case _ => None
    }
  }

  /** `initialize`, then `initialized`: the session */
  def connect[R[_]](name: String, version: String)(implicit in: In[Link, R]): Session[R] ! R =
    sendRpc[R](Rpc.Request(Json.num(0), "initialize", Json.obj("protocolVersion" -> Json.Str("2025-06-18"), "capabilities" -> Json.Obj.empty,
      "clientInfo" -> Json.obj("name" -> Json.Str(name), "version" -> Json.Str(version))))).flatMap { _ =>
      def await: Json ! R = receiveRpc[R].flatMap {
        case Some(Right(Rpc.Answer(Json.Num(n), result))) if n == BigDecimal(0) => pure[Json, R](result)
        case Some(_) => await
        case None => pure[Json, R](Json.Null)
      }
      await.flatMap(result => sendRpc[R](Rpc.Notify("notifications/initialized", Json.Obj.empty)).map(_ => new Session[R](result)))
    }
}

object Clients {
  def apply()(implicit fx: Effects): Clients[fx.type] = {
    implicit val exact: fx.type = fx
    new Clients[fx.type]
  }
}
