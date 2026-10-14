package at.energydash.testsupport

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.pattern.after

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._
import scala.xml.{Elem, XML}

/**
 * Stand-in for the Ponton X/P messenger on the loopback address of `app.kepserver.url` in
 * `application-test.conf` (127.0.0.1:16060). Records every request and answers by a script.
 * Tests never reach a real KEP endpoint (AGENTS.md §15).
 */
final class FakePonton(implicit system: ActorSystem[_]) {
  import FakePonton._
  import system.executionContext

  private val received = new ConcurrentLinkedQueue[Recorded]()
  @volatile private var answer: Answer = Ok

  private val route =
    extractRequest { req =>
      entity(as[String]) { body =>
        received.add(Recorded(req.uri.path.toString, req.headers.map(h => h.name -> h.value).toList, body))
        complete(respond(answer))
      }
    }

  private val binding = Await.result(Http().newServerAt("127.0.0.1", Port).bind(route), 10.seconds)

  private def respond(a: Answer): Future[HttpResponse] = a match {
    case Ok => Future.successful(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, "")))
    case Accepted => Future.successful(HttpResponse(StatusCodes.Accepted))
    case Status(code) => Future.successful(HttpResponse(StatusCodes.getForKey(code).getOrElse(StatusCodes.InternalServerError)))
    case Delay(d, then) => after(d)(respond(then))(system.classicSystem)
  }

  def script(a: Answer): Unit = answer = a

  def requests: List[Recorded] = received.asScala.toList

  def reset(): Unit = { received.clear(); answer = Ok }

  def stop(): Unit = Await.ready(binding.unbind(), 10.seconds)
}

object FakePonton {
  val Port = 16060

  sealed trait Answer
  case object Ok extends Answer
  case object Accepted extends Answer
  final case class Status(code: Int) extends Answer
  final case class Delay(d: FiniteDuration, then: Answer) extends Answer

  final case class Recorded(path: String, headers: List[(String, String)], body: String) {
    def header(name: String): Option[String] = headers.collectFirst { case (n, v) if n.equalsIgnoreCase(name) => v }
    def envelope: Elem = XML.loadString(body)
    /** `OutboundDocument/Header` of the SOAP body as name → text. */
    def pontonHeader: Map[String, String] = SoapEnvelope.header(envelope)
  }
}
