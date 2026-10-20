package okay.mcp

import okay.effects.Effects

/** THE TRANSPORT AS AN EFFECT: a line sent, a line received (`None`: the other end closed). Its handlers: the process's
 * standard streams (`stdio`), a script of lines for a test (`scripted`), a loopback to a server in the same process
 * (`loopback`): the protocol's code knows none of them */
sealed trait Link[X]
object Link {
  final case class Send[X](line: String, answer: Unit => X) extends Link[X]
  final case class Receive[X](answer: Option[String] => X) extends Link[X]
}

class Links[F <: Effects](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  def send[R[_]](line: String)(implicit in: In[Link, R]): Unit ! R = perform[Link].apply[R, Unit](Link.Send(line, u => u))
  def receive[R[_]](implicit in: In[Link, R]): Option[String] ! R = perform[Link].apply[R, Option[String]](Link.Receive(l => l))
  def sendRpc[R[_]](m: Rpc)(implicit in: In[Link, R]): Unit ! R = send[R](Rpc.encode(m))
  /** the next message: `Left` a malformed line, with the `Failed` to answer it */
  def receiveRpc[R[_]](implicit in: In[Link, R]): Option[Either[Rpc.Failed, Rpc]] ! R = receive[R].map(_.map(Rpc.decode))

  /** standard input and output; standard error is for logs, the protocol never sees it */
  def stdio: Stdio = new Stdio
  final class Stdio {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Link, R, Rest]): A ! Rest =
      handle[Link, R, Rest, A, A](p, new TailHandler[Link, Rest, A, A] {
        def ret(a: A) = pure(a)
        def answer[X](op: Link[X]): X = op match {
          case Link.Send(line, answer) => System.out.println(line); System.out.flush(); answer(())
          case Link.Receive(answer) => answer(Option(scala.io.StdIn.readLine()))
        }
      })
  }
  /** the lines received from a script, in order, `None` past them; the lines sent collected */
  def scripted(lines: List[String]): Scripted = new Scripted(lines)
  final class Scripted(lines: List[String]) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Link, R, Rest]): (List[String], A) ! Rest =
      handle[Link, (List[String], List[String]), R, Rest, A, (List[String], A)](p, (lines, Nil),
        new StatefulTailHandler[Link, (List[String], List[String]), Rest, A, (List[String], A)] {
          def ret(s: (List[String], List[String]), a: A) = pure((s._2.reverse, a))
          def answer[X](cell: Cell[(List[String], List[String])], op: Link[X]): X = op match {
            case Link.Send(line, answer) => cell.set((cell.get._1, line :: cell.get._2)); answer(())
            case Link.Receive(answer) => cell.get._1 match {
              case l :: rest => cell.set((rest, cell.get._2)); answer(Some(l))
              case Nil => answer(None)
            }
          }
        })
  }
  /** a client's link to a server in the same process: each line sent is given to `server`, whose lines come back as
   * the lines received — a program of the row outside, so a server of any effects */
  def loopback[Rest[_]](server: String => List[String] ! Rest): Loopback[Rest] = new Loopback[Rest](server)
  final class Loopback[Rest[_]](server: String => List[String] ! Rest) {
    def apply[R[_], A](p: A ! R)(implicit sp: Split[Link, R, Rest]): A ! Rest =
      handle[Link, List[String], R, Rest, A, A](p, Nil, new StatefulHandler[Link, List[String], Rest, A, A] {
        def ret(s: List[String], a: A) = pure(a)
        def op[X](s: List[String], op: Link[X], k: (X, List[String]) => A ! Rest) = op match {
          case Link.Send(line, answer) => server(line).flatMap(replies => k(answer(()), s ++ replies))
          case Link.Receive(answer) => s match {
            case l :: rest => k(answer(Some(l)), rest)
            case Nil => k(answer(None), Nil)
          }
        }
      })
  }
}
