package at.energydash.actors.http

import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublish}
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives._

import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.io.Source
import scala.jdk.CollectionConverters._

// konzept-eeg-umzug-pilot.md, "Weg B": inbound messages for a moved community go unchanged to the other zone
class PontonRouteForwardSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  private val envelope = Source.fromInputStream(getClass.getResourceAsStream("/Response-Envelope.xml"), "UTF-8").mkString
  private val soapAction = RawHeader("SOAPAction", "http://xp.ponton.de/eda/v320/inboundDocument")

  /** Stand-in for the pilot adapter: records requests, answers with the given status after the given delay. */
  private final case class Received(body: String, headers: Seq[HttpHeader])
  private def targetServer(status: StatusCode, delay: FiniteDuration = Duration.Zero) = {
    val received = new ConcurrentLinkedQueue[Received]()
    val route = path("pontonxp" / "message") {
      post {
        extractRequest { req =>
          entity(as[String]) { body =>
            received.add(Received(body, req.headers))
            complete(org.apache.pekko.pattern.after(delay)(Future.successful(HttpResponse(status))))
          }
        }
      }
    }
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), 5.seconds)
    (Uri(s"http://127.0.0.1:${binding.localAddress.getPort}/pontonxp/message"), received, binding)
  }

  private def adapter(targets: Map[String, Uri], probe: TestProbe[MqttCommand]) = {
    val routes = new PontonRoute(probe.ref, targets, forwardTimeout = 1.second)
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(routes.pontonRoutes), 5.seconds)
    (Uri(s"http://127.0.0.1:${binding.localAddress.getPort}/pontonxp/message"), binding)
  }

  private def send(uri: Uri, extraHeaders: Seq[HttpHeader] = Nil): StatusCode = {
    val req = HttpRequest(HttpMethods.POST, uri, soapAction +: extraHeaders,
      HttpEntity(ContentTypes.`text/xml(UTF-8)`, envelope))
    val res = Await.result(Http().singleRequest(req), 10.seconds)
    res.discardEntityBytes()
    res.status
  }

  "the message route" should {
    "forward a listed receiver unchanged and not publish it here" in {
      val probe = createTestProbe[MqttCommand]()
      val (target, received, tb) = targetServer(StatusCodes.NoContent)
      val (uri, ab) = adapter(Map("RC100181" -> target), probe)

      send(uri) shouldBe StatusCodes.NoContent
      val got = received.asScala.toList
      got should have size 1
      got.head.body shouldBe envelope
      got.head.headers.exists(h => h.is("soapaction") && h.value == soapAction.value) shouldBe true
      got.head.headers.exists(_.is(InboundForwarding.ForwardedHeader.toLowerCase)) shouldBe true
      probe.expectNoMessage(300.millis)
      tb.unbind(); ab.unbind()
    }

    "fail the request when the target does not accept it, so Ponton redelivers" in {
      val probe = createTestProbe[MqttCommand]()
      val (target, received, tb) = targetServer(StatusCodes.ServiceUnavailable)
      val (uri, ab) = adapter(Map("RC100181" -> target), probe)

      send(uri) shouldBe StatusCodes.InternalServerError
      received.size shouldBe 1
      probe.expectNoMessage(300.millis)
      tb.unbind(); ab.unbind()
    }

    "fail the request when the target answers too late" in {
      val probe = createTestProbe[MqttCommand]()
      val (target, _, tb) = targetServer(StatusCodes.NoContent, delay = 3.seconds)
      val (uri, ab) = adapter(Map("RC100181" -> target), probe)

      send(uri) shouldBe StatusCodes.InternalServerError
      probe.expectNoMessage(300.millis)
      tb.unbind(); ab.unbind()
    }

    "fail the request when the target is unreachable" in {
      val probe = createTestProbe[MqttCommand]()
      val (uri, ab) = adapter(Map("RC100181" -> Uri("http://127.0.0.1:1/pontonxp/message")), probe)

      send(uri) shouldBe StatusCodes.InternalServerError
      probe.expectNoMessage(300.millis)
      ab.unbind()
    }

    "refuse an already forwarded message instead of forwarding it again" in {
      val probe = createTestProbe[MqttCommand]()
      val (target, received, tb) = targetServer(StatusCodes.NoContent)
      val (uri, ab) = adapter(Map("RC100181" -> target), probe)

      send(uri, Seq(RawHeader(InboundForwarding.ForwardedHeader, "true"))) shouldBe StatusCodes.InternalServerError
      received.size shouldBe 0
      probe.expectNoMessage(300.millis)
      tb.unbind(); ab.unbind()
    }

    "process other receivers here as before" in {
      val probe = createTestProbe[MqttCommand]()
      val (target, received, tb) = targetServer(StatusCodes.NoContent)
      val (uri, ab) = adapter(Map("CC100153" -> target), probe)

      send(uri) shouldBe StatusCodes.NoContent
      probe.expectMessageType[MqttPublish](3.seconds)
      received.size shouldBe 0
      tb.unbind(); ab.unbind()
    }

    "process an already forwarded message here when this zone does not forward its receiver" in {
      val probe = createTestProbe[MqttCommand]()
      val (uri, ab) = adapter(Map.empty, probe)

      send(uri, Seq(RawHeader(InboundForwarding.ForwardedHeader, "true"))) shouldBe StatusCodes.NoContent
      probe.expectMessageType[MqttPublish](3.seconds)
      ab.unbind()
    }
  }
}
