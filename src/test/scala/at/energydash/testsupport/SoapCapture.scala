package at.energydash.testsupport

import at.energydash.actors.http.{AkkaHttpClients, AkkaHttpHandler}
import at.energydash.actors.soap.PontonRequest
import at.energydash.domain.EbMsMessage
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model._

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.xml.{Elem, XML}

/**
 * Runs the production `PontonRequest` with its HTTP seam (`AkkaHttpHandler.sendRequest`) replaced, so the
 * SOAP request is captured exactly as it would go to Ponton — nothing leaves the JVM.
 */
object SoapCapture {
  final case class Captured(uri: Uri, headers: Map[String, String], contentType: String, body: String) {
    /** The request as XML; a request that is not well-formed fails with the raw body in the message. */
    lazy val envelope: Elem =
      try XML.loadString(body)
      catch { case e: org.xml.sax.SAXParseException => throw new AssertionError(s"SOAP request is not well-formed XML (${e.getMessage}):\n$body", e) }
    def pontonHeader: Map[String, String] = SoapEnvelope.header(envelope)
    def document: Elem = SoapEnvelope.document(envelope)
  }

  def send(message: EbMsMessage)(implicit system: ActorSystem[_]): Captured = {
    val seen = new AtomicReference[Captured]()
    trait Capturing extends AkkaHttpHandler with AkkaHttpClients {
      override def sendRequest(r: HttpRequest)(implicit as: ActorSystem[_]): Future[HttpResponse] = {
        val body = r.entity match {
          case s: HttpEntity.Strict => s.data.utf8String
          case other => sys.error(s"unexpected entity $other")
        }
        seen.set(Captured(r.uri, r.headers.map(h => h.name -> h.value).toMap, r.entity.contentType.toString, body))
        Future.successful(HttpResponse(StatusCodes.OK))
      }
    }
    val request = new PontonRequest(system) with Capturing
    Await.result(request.service.sendRequest(message)(system.executionContext), 10.seconds)
    Option(seen.get).getOrElse(sys.error("no HTTP request was sent"))
  }
}
