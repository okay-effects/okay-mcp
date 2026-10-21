package okay.mcp

import okay.effects.Effects
import okay.effects.json.Json

final case class ToolSpec(name: String, description: String, inputSchema: Json, outputSchema: Option[Json] = None) {
  def json: Json = Json.Obj(Vector("name" -> Json.Str(name), "description" -> Json.Str(description), "inputSchema" -> inputSchema) ++ outputSchema.map("outputSchema" -> _))
}
final case class ToolCall(name: String, arguments: Json)
sealed trait Content
object Content {
  final case class Text(text: String) extends Content
  def json(c: Content): Json = c match { case Text(t) => Json.obj("type" -> Json.Str("text"), "text" -> Json.Str(t)) }
}
final case class ToolResult(content: List[Content], structured: Option[Json] = None, isError: Boolean = false) {
  def json: Json = Json.Obj(Vector("content" -> Json.Arr(content.map(Content.json).toVector)) ++ structured.map("structuredContent" -> _) ++
    (if (isError) Vector("isError" -> Json.Bool(true)) else Vector.empty))
}
object ToolResult {
  def text(s: String): ToolResult = ToolResult(List(Content.Text(s)))
  def error(s: String): ToolResult = ToolResult(List(Content.Text(s)), None, isError = true)
}
final case class ResourceSpec(uri: String, name: String, mimeType: Option[String] = None, description: Option[String] = None) {
  def json: Json = Json.Obj(Vector("uri" -> Json.Str(uri), "name" -> Json.Str(name)) ++ mimeType.map(m => "mimeType" -> Json.Str(m)) ++ description.map(d => "description" -> Json.Str(d)))
}
final case class ResourceContent(uri: String, mimeType: String, text: String) {
  def json: Json = Json.obj("uri" -> Json.Str(uri), "mimeType" -> Json.Str(mimeType), "text" -> Json.Str(text))
}

/** THE SERVER over any implementation: `serve(server)` the loop over `Link` — `initialize`, then requests until the link
 * closes; `dispatch` one message to its replies, pure in its state, so a loopback can run it; `elicit` a question to
 * the client's user, from inside a tool's call */
class Servers[F <: Effects with Singleton](implicit implementation: F) {
  val fx: F = implementation
  import fx._
  val links: Links[fx.type] = new Links[fx.type]()(fx)
  import links.{receiveRpc, sendRpc}

  trait Tools[R[_]] {
    def list: List[ToolSpec] ! R
    def call(c: ToolCall): ToolResult ! R
  }
  trait Resources[R[_]] {
    def list: List[ResourceSpec] ! R
    def read(uri: String): List[ResourceContent] ! R
  }
  final case class Server[R[_]](name: String, version: String, tools: Tools[R], resources: Option[Resources[R]] = None,
                                instructions: Option[String] = None, elicitation: Boolean = false)

  val protocolVersion = "2025-06-18"
  /** the session: initialised yet, the tools last listed (to say `list_changed`) */
  final case class Session(initialized: Boolean, lastTools: Option[List[ToolSpec]])
  val fresh: Session = Session(initialized = false, None)

  private def str(j: Json, k: String): Option[String] = j match { case o: Json.Obj => o.get(k).collect { case Json.Str(s) => s }; case _ => None }

  /** one message in, the replies out — the answer, and a `list_changed` when the tools changed */
  def dispatch[R[_]](server: Server[R], s: Session, m: Rpc): (Session, List[Rpc]) ! R = m match {
    case Rpc.Request(id, "initialize", _) =>
      val caps = Json.Obj(Vector("tools" -> Json.obj("listChanged" -> Json.Bool(true))) ++
        server.resources.map(_ => "resources" -> Json.obj("listChanged" -> Json.Bool(true), "subscribe" -> Json.Bool(false))) ++
        (if (server.elicitation) Vector("elicitation" -> Json.Obj.empty) else Vector.empty))
      val result = Json.Obj(Vector("protocolVersion" -> Json.Str(protocolVersion), "capabilities" -> caps,
        "serverInfo" -> Json.obj("name" -> Json.Str(server.name), "version" -> Json.Str(server.version))) ++ server.instructions.map(i => "instructions" -> Json.Str(i)))
      pure[(Session, List[Rpc]), R]((s.copy(initialized = true), List(Rpc.Answer(id, result))))
    case Rpc.Notify(_, _) => pure[(Session, List[Rpc]), R]((s, Nil))
    case Rpc.Request(id, _, _) if !s.initialized => pure[(Session, List[Rpc]), R]((s, List(Rpc.Failed(id, Rpc.Code.InvalidRequest, "initialize first"))))
    case Rpc.Request(id, "ping", _) => pure[(Session, List[Rpc]), R]((s, List(Rpc.Answer(id, Json.Obj.empty))))
    case Rpc.Request(id, "tools/list", _) => server.tools.list.map(ts => (s.copy(lastTools = Some(ts)), List(Rpc.Answer(id, Json.obj("tools" -> Json.Arr(ts.map(_.json).toVector))))))
    case Rpc.Request(id, "tools/call", params) => str(params, "name") match {
      case None => pure[(Session, List[Rpc]), R]((s, List(Rpc.Failed(id, Rpc.Code.InvalidParams, "a tool's name is required"))))
      case Some(name) =>
        val args = params match { case o: Json.Obj => o.get("arguments").getOrElse(Json.Obj.empty); case _ => Json.Obj.empty }
        server.tools.call(ToolCall(name, args)).flatMap { result =>
          server.tools.list.map { ts =>
            val changed = s.lastTools.exists(_ != ts)
            (s.copy(lastTools = Some(ts)), Rpc.Answer(id, result.json) :: (if (changed) List(Rpc.Notify("notifications/tools/list_changed", Json.Obj.empty)) else Nil))
          }
        }
    }
    case Rpc.Request(id, "resources/list", _) => server.resources match {
      case Some(r) => r.list.map(rs => (s, List(Rpc.Answer(id, Json.obj("resources" -> Json.Arr(rs.map(_.json).toVector))))))
      case None => pure[(Session, List[Rpc]), R]((s, List(Rpc.Failed(id, Rpc.Code.MethodNotFound, "no resources"))))
    }
    case Rpc.Request(id, "resources/read", params) => (server.resources, str(params, "uri")) match {
      case (Some(r), Some(uri)) => r.read(uri).map(cs => (s, List(Rpc.Answer(id, Json.obj("contents" -> Json.Arr(cs.map(_.json).toVector))))))
      case (None, _) => pure[(Session, List[Rpc]), R]((s, List(Rpc.Failed(id, Rpc.Code.MethodNotFound, "no resources"))))
      case (_, None) => pure[(Session, List[Rpc]), R]((s, List(Rpc.Failed(id, Rpc.Code.InvalidParams, "a uri is required"))))
    }
    case Rpc.Request(id, method, _) => pure[(Session, List[Rpc]), R]((s, List(Rpc.Failed(id, Rpc.Code.MethodNotFound, s"unknown method: $method"))))
    case Rpc.Answer(_, _) | Rpc.Failed(_, _, _, _) => pure[(Session, List[Rpc]), R]((s, Nil))   // an answer to no question of ours
  }

  /** the loop: messages received, dispatched, replies sent, until the link closes */
  def serve[R[_]](server: Server[R])(implicit in: In[Link, R]): Unit ! R = {
    def loop(s: Session): Unit ! R = receiveRpc[R].flatMap {
      case None => pure[Unit, R](())
      case Some(Left(malformed)) => sendRpc[R](malformed).flatMap(_ => loop(s))
      case Some(Right(m)) => dispatch[R](server, s, m).flatMap { case (next, replies) =>
        replies.foldLeft(pure[Unit, R](()))((acc, r) => acc.flatMap(_ => sendRpc[R](r))).flatMap(_ => loop(next))
      }
    }
    loop(fresh)
  }

  /** a question to the client's USER, from inside a tool's call: `elicitation/create` with a message and a JSON schema
   * of the answer; the client's answer, or `None` when declined or cancelled. Other requests arriving meanwhile are
   * answered "busy" */
  final case class Declined(action: String)
  /** the ids of elicitations, counted: `elicit-1`, `elicit-2`… */
  private var elicited = 0
  def elicit[R[_]](message: String, schema: Json)(implicit in: In[Link, R]): Either[Declined, Json] ! R = {
    elicited += 1
    val id = Json.Str("elicit-" + elicited)
    sendRpc[R](Rpc.Request(id, "elicitation/create", Json.obj("message" -> Json.Str(message), "requestedSchema" -> schema))).flatMap { _ =>
      def await: Either[Declined, Json] ! R = receiveRpc[R].flatMap {
        case None => pure[Either[Declined, Json], R](Left(Declined("closed")))
        case Some(Left(malformed)) => sendRpc[R](malformed).flatMap(_ => await)
        case Some(Right(Rpc.Answer(i, result))) if i == id =>
          pure[Either[Declined, Json], R](str(result, "action") match {
            case Some("accept") => Right(result match { case o: Json.Obj => o.get("content").getOrElse(Json.Obj.empty); case _ => Json.Obj.empty })
            case Some(other) => Left(Declined(other))
            case None => Left(Declined("no action"))
          })
        case Some(Right(Rpc.Failed(i, _, message, _))) if i == id => pure[Either[Declined, Json], R](Left(Declined(message)))
        case Some(Right(Rpc.Request(other, _, _))) => sendRpc[R](Rpc.Failed(other, Rpc.Code.InvalidRequest, "busy: a question to the user is pending")).flatMap(_ => await)
        case Some(Right(_)) => await
      }
      await
    }
  }
}

object Servers {
  def apply()(implicit fx: Effects): Servers[fx.type] = {
    implicit val exact: fx.type = fx
    new Servers[fx.type]
  }
}
