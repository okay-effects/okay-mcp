package okay.mcp

import okay.effects.json.{Descent, Json}

/** JSON-RPC 2.0 AS DATA, total both ways: a damaged line is `Left` of the `Failed` to answer it with (code -32700, or
 * -32600 for a message of the wrong shape), never a throw; the id of any JSON kind is kept as it is */
sealed trait Rpc
object Rpc {
  final case class Request(id: Json, method: String, params: Json) extends Rpc
  final case class Notify(method: String, params: Json) extends Rpc
  final case class Answer(id: Json, result: Json) extends Rpc
  final case class Failed(id: Json, code: Int, message: String, data: Option[Json] = None) extends Rpc

  object Code {
    val Parse = -32700; val InvalidRequest = -32600; val MethodNotFound = -32601; val InvalidParams = -32602; val Internal = -32603
  }

  def decode(line: String): Either[Failed, Rpc] = Descent.parse(line) match {
    case Left(e) => Left(Failed(Json.Null, Code.Parse, e.message))
    case Right(o: Json.Obj) =>
      (o.get("method"), o.get("id"), o.get("result"), o.get("error")) match {
        case (Some(Json.Str(m)), Some(id), _, _) => Right(Request(id, m, o.get("params").getOrElse(Json.Obj.empty)))
        case (Some(Json.Str(m)), None, _, _) => Right(Notify(m, o.get("params").getOrElse(Json.Obj.empty)))
        case (None, Some(id), Some(r), None) => Right(Answer(id, r))
        case (None, Some(id), _, Some(e: Json.Obj)) =>
          Right(Failed(id, e.get("code").collect { case Json.Num(n) => n.toInt }.getOrElse(Code.Internal), e.get("message").collect { case Json.Str(s) => s }.getOrElse(""), e.get("data")))
        case _ => Left(Failed(o.get("id").getOrElse(Json.Null), Code.InvalidRequest, "not a request, a notification, an answer or an error"))
      }
    case Right(other) => Left(Failed(Json.Null, Code.InvalidRequest, s"a JSON-RPC message is an object, not ${Json.kind(other)}"))
  }
  def encode(m: Rpc): String = Json.print(json(m))
  def json(m: Rpc): Json = m match {
    case Request(id, method, params) => Json.obj("jsonrpc" -> Json.Str("2.0"), "id" -> id, "method" -> Json.Str(method), "params" -> params)
    case Notify(method, params) => Json.obj("jsonrpc" -> Json.Str("2.0"), "method" -> Json.Str(method), "params" -> params)
    case Answer(id, result) => Json.obj("jsonrpc" -> Json.Str("2.0"), "id" -> id, "result" -> result)
    case Failed(id, code, message, data) =>
      Json.obj("jsonrpc" -> Json.Str("2.0"), "id" -> id, "error" -> Json.Obj(Vector("code" -> Json.num(code), "message" -> Json.Str(message)) ++ data.map("data" -> _)))
  }
}
