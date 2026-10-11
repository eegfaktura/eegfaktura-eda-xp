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
  /** Same message, padded with an XML comment to more than 8 MiB (pekko's default max-to-strict-bytes). */
  private lazy val largeEnvelope = envelope.replace("</SOAP-ENV:Envelope>", "<!--" + ("x" * (9 * 1024 * 1024)) + "--></SOAP-ENV:Envelope>")
  private val soapAction = RawHeader("SOAPAction", "http://xp.ponton.de/eda/v320/inboundDocument")
  private val forwardedHeader = RawHeader(InboundForwarding.ForwardedHeader, "true")

  private final case class Received(body: String, headers: Seq[HttpHeader])
  private final case class Target(uri: Uri, received: ConcurrentLinkedQueue[Received])

  /** Stand-in for the pilot adapter: records requests, answers with the given status after the given delay. */
  private def withTarget[T](status: StatusCode, delay: FiniteDuration = Duration.Zero)(test: Target => T): T = {
    val received = new ConcurrentLinkedQueue[Received]()
    // no size limit, like the real target adapter with an empty app.forward
    val route = path("pontonxp" / "message") {
      (post & withoutSizeLimit) {
        extractRequest { req =>
          entity(as[String]) { body =>
            received.add(Received(body, req.headers))
            complete(org.apache.pekko.pattern.after(delay)(Future.successful(HttpResponse(status))))
          }
        }
      }
    }
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), 5.seconds)
    try test(Target(Uri(s"http://127.0.0.1:${binding.localAddress.getPort}/pontonxp/message"), received))
    finally Await.ready(binding.unbind(), 5.seconds)
  }

  private def withAdapter[T](targets: Map[String, Uri], probe: TestProbe[MqttCommand])(test: Uri => T): T = {
    val routes = new PontonRoute(probe.ref, targets, forwardTimeout = 1.second)
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(routes.pontonRoutes), 5.seconds)
    try test(Uri(s"http://127.0.0.1:${binding.localAddress.getPort}/pontonxp/message"))
    finally Await.ready(binding.unbind(), 5.seconds)
  }

  private def send(uri: Uri, extraHeaders: Seq[HttpHeader] = Nil, body: String = envelope): StatusCode = {
    val req = HttpRequest(HttpMethods.POST, uri, soapAction +: extraHeaders, HttpEntity(ContentTypes.`text/xml(UTF-8)`, body))
    val res = Await.result(Http().singleRequest(req), 15.seconds)
    res.discardEntityBytes()
    res.status
  }

  "the message route" should {
    "forward a listed receiver unchanged and not publish it here" in {
      val probe = createTestProbe[MqttCommand]()
      withTarget(StatusCodes.NoContent) { target =>
        withAdapter(Map("RC100181" -> target.uri), probe) { uri =>
          send(uri) shouldBe StatusCodes.NoContent
          val got = target.received.asScala.toList
          got should have size 1
          got.head.body shouldBe envelope
          got.head.headers.exists(h => h.is("soapaction") && h.value == soapAction.value) shouldBe true
          got.head.headers.exists(_.is(InboundForwarding.ForwardedHeader.toLowerCase)) shouldBe true
          probe.expectNoMessage(300.millis)
        }
      }
    }

    "fail the request when the target does not accept it, so Ponton redelivers" in {
      val probe = createTestProbe[MqttCommand]()
      withTarget(StatusCodes.ServiceUnavailable) { target =>
        withAdapter(Map("RC100181" -> target.uri), probe) { uri =>
          send(uri) shouldBe StatusCodes.InternalServerError
          target.received.size shouldBe 1
          probe.expectNoMessage(300.millis)
        }
      }
    }

    "fail the request when the target answers too late" in {
      val probe = createTestProbe[MqttCommand]()
      withTarget(StatusCodes.NoContent, delay = 3.seconds) { target =>
        withAdapter(Map("RC100181" -> target.uri), probe) { uri =>
          send(uri) shouldBe StatusCodes.InternalServerError
          probe.expectNoMessage(300.millis)
        }
      }
    }

    "fail the request when the target is unreachable" in {
      val probe = createTestProbe[MqttCommand]()
      withAdapter(Map("RC100181" -> Uri("http://127.0.0.1:1/pontonxp/message")), probe) { uri =>
        send(uri) shouldBe StatusCodes.InternalServerError
        probe.expectNoMessage(300.millis)
      }
    }

    "refuse an already forwarded message instead of forwarding it again" in {
      val probe = createTestProbe[MqttCommand]()
      withTarget(StatusCodes.NoContent) { target =>
        withAdapter(Map("RC100181" -> target.uri), probe) { uri =>
          send(uri, Seq(forwardedHeader)) shouldBe StatusCodes.InternalServerError
          target.received.size shouldBe 0
          probe.expectNoMessage(300.millis)
        }
      }
    }

    "process other receivers here as before" in {
      val probe = createTestProbe[MqttCommand]()
      withTarget(StatusCodes.NoContent) { target =>
        withAdapter(Map("CC100153" -> target.uri), probe) { uri =>
          send(uri) shouldBe StatusCodes.NoContent
          probe.expectMessageType[MqttPublish](3.seconds)
          target.received.size shouldBe 0
        }
      }
    }

    "process an already forwarded message here when this zone does not forward its receiver" in {
      val probe = createTestProbe[MqttCommand]()
      withAdapter(Map.empty, probe) { uri =>
        send(uri, Seq(forwardedHeader)) shouldBe StatusCodes.NoContent
        probe.expectMessageType[MqttPublish](3.seconds)
      }
    }

    "process a message over 8 MiB as before when nothing is forwarded" in {
      val probe = createTestProbe[MqttCommand]()
      withAdapter(Map.empty, probe) { uri =>
        send(uri, body = largeEnvelope) shouldBe StatusCodes.NoContent
        probe.expectMessageType[MqttPublish](10.seconds)
      }
    }

    "forward a message over 8 MiB for a listed receiver unchanged" in {
      val probe = createTestProbe[MqttCommand]()
      withTarget(StatusCodes.NoContent) { target =>
        withAdapter(Map("RC100181" -> target.uri), probe) { uri =>
          send(uri, body = largeEnvelope) shouldBe StatusCodes.NoContent
          val got = target.received.asScala.toList
          got should have size 1
          got.head.body.length shouldBe largeEnvelope.length
          got.head.body shouldBe largeEnvelope
          probe.expectNoMessage(300.millis)
        }
      }
    }

    "process a message over 8 MiB for an unlisted receiver while others are forwarded" in {
      val probe = createTestProbe[MqttCommand]()
      withAdapter(Map("CC100153" -> Uri("http://127.0.0.1:1/pontonxp/message")), probe) { uri =>
        send(uri, body = largeEnvelope) shouldBe StatusCodes.NoContent
        probe.expectMessageType[MqttPublish](10.seconds)
      }
    }
  }
}
