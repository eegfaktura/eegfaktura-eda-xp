package at.energydash.actors.http

import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublish}
import at.energydash.service.FileService
import com.typesafe.config.ConfigFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

// eda-xp #3: bodies read completely into memory are capped at app.max-body-size
class BodySizeLimitSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private val envelope = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/Response-Envelope.xml"), "UTF-8").mkString
  // a low limit from config, so the test bodies stay small
  private val limit = BodySizeLimit.maxBytes(ConfigFactory.parseString("app.max-body-size = 64k"))
  /** Same message, padded with an XML comment to more than the limit. */
  private val largeEnvelope = envelope.replace("</SOAP-ENV:Envelope>", "<!--" + ("x" * (limit.toInt + 1)) + "--></SOAP-ENV:Envelope>")
  private val soapAction = RawHeader("SOAPAction", "http://xp.ponton.de/eda/v320/inboundDocument")

  private def withServer[T](route: Route)(test: Uri => T): T = {
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), 5.seconds)
    try test(Uri(s"http://127.0.0.1:${binding.localAddress.getPort}"))
    finally Await.ready(binding.unbind(), 5.seconds)
  }

  private def ponton(probe: TestProbe[MqttCommand], targets: Map[String, Uri] = Map.empty): Route =
    new PontonRoute(probe.ref, targets, forwardTimeout = 1.second, maxBodyBytes = limit).pontonRoutes

  private def send(uri: Uri, entity: RequestEntity): StatusCode = {
    val res = Await.result(Http().singleRequest(HttpRequest(HttpMethods.POST, uri, soapAction :: Nil, entity)), 15.seconds)
    res.discardEntityBytes()
    res.status
  }

  private def xml(body: String) = HttpEntity(ContentTypes.`text/xml(UTF-8)`, body)
  /** Without Content-Length: the limit applies while reading. */
  private def chunked(body: String) =
    HttpEntity.Chunked.fromData(ContentTypes.`text/xml(UTF-8)`, Source(ByteString(body).grouped(8192).toList))

  "app.max-body-size" should {
    "default to 128 MiB" in {
      BodySizeLimit.maxBytes(ConfigFactory.empty()) shouldBe 128L * 1024 * 1024
      BodySizeLimit.maxBytes(ConfigFactory.load()) shouldBe 128L * 1024 * 1024
      limit shouldBe 64 * 1024
    }
  }

  "the message route" should {
    "accept a message below the limit" in {
      val probe = createTestProbe[MqttCommand]()
      withServer(ponton(probe)) { base =>
        send(base.withPath(Uri.Path("/pontonxp/message")), xml(envelope)) shouldBe StatusCodes.NoContent
        probe.expectMessageType[MqttPublish](3.seconds)
      }
    }

    "reject a message above the limit with 413 and not process it" in {
      val probe = createTestProbe[MqttCommand]()
      withServer(ponton(probe)) { base =>
        send(base.withPath(Uri.Path("/pontonxp/message")), xml(largeEnvelope)) shouldBe StatusCodes.ContentTooLarge
        probe.expectNoMessage(300.millis)
      }
    }

    "reject a chunked message above the limit with 413" in {
      val probe = createTestProbe[MqttCommand]()
      withServer(ponton(probe)) { base =>
        send(base.withPath(Uri.Path("/pontonxp/message")), chunked(largeEnvelope)) shouldBe StatusCodes.ContentTooLarge
        probe.expectNoMessage(300.millis)
      }
    }

    "accept a chunked message below the limit" in {
      val probe = createTestProbe[MqttCommand]()
      withServer(ponton(probe)) { base =>
        send(base.withPath(Uri.Path("/pontonxp/message")), chunked(envelope)) shouldBe StatusCodes.NoContent
        probe.expectMessageType[MqttPublish](3.seconds)
      }
    }

    // forwarding reads the body strictly (toStrictEntity): the limit applies there as well
    "reject a message above the limit with 413 while communities are forwarded" in {
      val probe = createTestProbe[MqttCommand]()
      withServer(ponton(probe, Map("RC100181" -> Uri("http://127.0.0.1:1/pontonxp/message")))) { base =>
        send(base.withPath(Uri.Path("/pontonxp/message")), xml(largeEnvelope)) shouldBe StatusCodes.ContentTooLarge
        send(base.withPath(Uri.Path("/pontonxp/message")), chunked(largeEnvelope)) shouldBe StatusCodes.ContentTooLarge
        probe.expectNoMessage(300.millis)
      }
    }

    "accept a message below the limit while communities are forwarded" in {
      val probe = createTestProbe[MqttCommand]()
      withServer(ponton(probe, Map("CC100153" -> Uri("http://127.0.0.1:1/pontonxp/message")))) { base =>
        send(base.withPath(Uri.Path("/pontonxp/message")), chunked(envelope)) shouldBe StatusCodes.NoContent
        probe.expectMessageType[MqttPublish](3.seconds)
      }
    }
  }

  "the default limit" should {
    "accept a message above 16 MiB (logged with its size)" in {
      val probe = createTestProbe[MqttCommand]()
      val body = envelope.replace("</SOAP-ENV:Envelope>", "<!--" + ("x" * (17 * 1024 * 1024)) + "--></SOAP-ENV:Envelope>")
      withServer(new PontonRoute(probe.ref).pontonRoutes) { base =>
        send(base.withPath(Uri.Path("/pontonxp/message")), xml(body)) shouldBe StatusCodes.NoContent
        probe.expectMessageType[MqttPublish](10.seconds)
      }
    }
  }

  "the admin upload" should {
    "reject a body above the limit with 413 and not process it" in {
      val calls = new AtomicInteger()
      val sys = system
      val files = new FileService {
        implicit val system: ActorSystem[_] = sys
        implicit val mat: Materializer = Materializer(sys)
        def handleUpload(formData: Multipart.FormData, ecId: Option[String]): Future[Done] = {
          calls.incrementAndGet()
          formData.parts.runForeach(_.entity.discardBytes()).map(_ => Done)(sys.executionContext)
        }
      }
      def upload(body: String) = Multipart.FormData(Multipart.FormData.BodyPart.Strict("file", HttpEntity(body))).toEntity
      withServer(new ServiceRoute(files, limit).adminRoutes) { base =>
        send(base.withPath(Uri.Path("/admin/upload")), upload("x" * (limit.toInt + 1))) shouldBe StatusCodes.ContentTooLarge
        calls.get shouldBe 0
        send(base.withPath(Uri.Path("/admin/upload")), upload("x" * 100)) shouldBe StatusCodes.Created
      }
    }
  }
}
