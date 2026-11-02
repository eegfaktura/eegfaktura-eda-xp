package at.energydash.actors.http

import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublish}
import at.energydash.service.FileService
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import ch.qos.logback.core.read.ListAppender
import com.typesafe.config.ConfigFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._

// eda-xp #3: bodies read completely into memory are capped at app.max-body-size
class BodySizeLimitSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private val envelope = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/Response-Envelope.xml"), "UTF-8").mkString
  // a low limit from config, so the test bodies stay small
  private val limit = BodySizeLimit.maxBytes(ConfigFactory.parseString("app.max-body-size = 64k"))
  /** Same message, padded with an XML comment to more than the limit. */
  private val largeEnvelope = envelope.replace("</SOAP-ENV:Envelope>", "<!--" + ("x" * (limit.toInt + 1)) + "--></SOAP-ENV:Envelope>")
  private val soapAction = RawHeader("SOAPAction", "http://xp.ponton.de/eda/v320/inboundDocument")
  private val TooLarge = BodySizeLimit.TooLargeStatus
  private val message = Uri.Path("/pontonxp/message")
  private val adminUpload = Uri.Path("/admin/upload")

  private def withServer[T](route: Route)(test: Uri => T): T = {
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), 5.seconds)
    try test(Uri(s"http://127.0.0.1:${binding.localAddress.getPort}"))
    finally Await.ready(binding.unbind(), 5.seconds)
  }

  private def ponton(probe: TestProbe[MqttCommand], targets: Map[String, Uri] = Map.empty, maxBytes: Long = limit): Route =
    new PontonRoute(testKit.spawn(AckingPublisher(probe.ref)), targets, forwardTimeout = 1.second, maxBodyBytes = maxBytes).pontonRoutes

  /** Upload handler that, like FileServiceImpl, publishes each part as soon as it has read it. */
  private def uploads(probe: TestProbe[MqttCommand]): FileService = {
    val sys = system
    new FileService {
      implicit val system: ActorSystem[_] = sys
      implicit val mat: Materializer = Materializer(sys)
      def handleUpload(formData: Multipart.FormData, ecId: Option[String]): Future[Done] =
        formData.parts.mapAsync(1)(_.entity.toStrict(5.seconds)).runForeach(_ => probe.ref ! MqttPublish(Nil))
    }
  }

  private def admin(probe: TestProbe[MqttCommand], maxBytes: Long = limit): Route =
    new ServiceRoute(uploads(probe), maxBytes).adminRoutes

  /** Events logged by the given route class while the test runs. */
  private def withLog[T](cls: Class[_])(test: (() => List[ILoggingEvent]) => T): T = {
    val logger = LoggerFactory.getLogger(cls).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    try test(() => appender.synchronized(appender.list.asScala.toList))
    finally logger.detachAppender(appender)
  }

  private def logged(events: List[ILoggingEvent], level: Level, text: String): Boolean =
    events.exists(e => e.getLevel == level && e.getFormattedMessage.contains(text))

  private def send(uri: Uri, entity: RequestEntity): StatusCode = {
    val res = Await.result(Http().singleRequest(HttpRequest(HttpMethods.POST, uri, soapAction :: Nil, entity)), 15.seconds)
    res.discardEntityBytes()
    res.status
  }

  private def xml(body: String) = HttpEntity(ContentTypes.`text/xml(UTF-8)`, body)
  /** Without Content-Length: the limit applies while reading. */
  private def chunked(body: String) =
    HttpEntity.Chunked.fromData(ContentTypes.`text/xml(UTF-8)`, Source(ByteString(body).grouped(8192).toList))

  private def upload(body: String) = Multipart.FormData(Multipart.FormData.BodyPart.Strict("file", HttpEntity(body))).toEntity
  /** Several parts without Content-Length (chunked), together above the limit when parts * partBytes is. */
  private def chunkedUpload(parts: Int, partBytes: Int) =
    Multipart.FormData(Source(1.to(parts).toList).map(i => Multipart.FormData.BodyPart(s"part$i",
      HttpEntity.IndefiniteLength(ContentTypes.`application/octet-stream`, Source.single(ByteString("x" * partBytes)))))).toEntity

  "app.max-body-size" should {
    "default to 128 MiB from reference.conf, answered with 413" in {
      BodySizeLimit.maxBytes(ConfigFactory.load()) shouldBe 128L * 1024 * 1024
      limit shouldBe 64 * 1024
      TooLarge shouldBe StatusCodes.ContentTooLarge
    }
  }

  "the message route" should {
    "accept a message below the limit" in {
      val probe = createTestProbe[MqttCommand]()
      withServer(ponton(probe)) { base =>
        send(base.withPath(message), xml(envelope)) shouldBe StatusCodes.NoContent
        probe.expectMessageType[MqttPublish](3.seconds)
      }
    }

    "reject a message above the limit, log its declared size and not process it" in {
      val probe = createTestProbe[MqttCommand]()
      withLog(classOf[PontonRoute]) { log =>
        withServer(ponton(probe)) { base =>
          send(base.withPath(message), xml(largeEnvelope)) shouldBe TooLarge
          probe.expectNoMessage(300.millis)
          logged(log(), Level.WARN, s"Ponton request rejected: body larger than the limit of $limit bytes (declared size: ${ByteString(largeEnvelope).length} bytes)") shouldBe true
        }
      }
    }

    "reject a chunked message above the limit" in {
      val probe = createTestProbe[MqttCommand]()
      withLog(classOf[PontonRoute]) { log =>
        withServer(ponton(probe)) { base =>
          send(base.withPath(message), chunked(largeEnvelope)) shouldBe TooLarge
          probe.expectNoMessage(300.millis)
          logged(log(), Level.WARN, "(declared size: none)") shouldBe true
        }
      }
    }

    "accept a chunked message below the limit" in {
      val probe = createTestProbe[MqttCommand]()
      withServer(ponton(probe)) { base =>
        send(base.withPath(message), chunked(envelope)) shouldBe StatusCodes.NoContent
        probe.expectMessageType[MqttPublish](3.seconds)
      }
    }

    // forwarding reads the body strictly (toStrictEntity): the limit applies there as well
    "reject a message above the limit while communities are forwarded" in {
      val probe = createTestProbe[MqttCommand]()
      withServer(ponton(probe, Map("RC100181" -> Uri("http://127.0.0.1:1/pontonxp/message")))) { base =>
        send(base.withPath(message), xml(largeEnvelope)) shouldBe TooLarge
        send(base.withPath(message), chunked(largeEnvelope)) shouldBe TooLarge
        probe.expectNoMessage(300.millis)
      }
    }

    "accept a message below the limit while communities are forwarded" in {
      val probe = createTestProbe[MqttCommand]()
      withServer(ponton(probe, Map("CC100153" -> Uri("http://127.0.0.1:1/pontonxp/message")))) { base =>
        send(base.withPath(message), chunked(envelope)) shouldBe StatusCodes.NoContent
        probe.expectMessageType[MqttPublish](3.seconds)
      }
    }

    "log a target zone that refuses a forwarded message as too large, and still answer 500" in {
      val probe = createTestProbe[MqttCommand]()
      val target = path("pontonxp" / "message") { complete(HttpResponse(StatusCodes.ContentTooLarge)) }
      withLog(classOf[PontonRoute]) { log =>
        withServer(target) { targetBase =>
          withServer(ponton(probe, Map("RC100181" -> targetBase.withPath(message)))) { base =>
            send(base.withPath(message), xml(envelope)) shouldBe StatusCodes.InternalServerError
            probe.expectNoMessage(300.millis)
            logged(log(), Level.ERROR, "refused as too large") shouldBe true
          }
        }
      }
    }

    "accept a message above 16 MiB with the default limit and log its size, code and conversation" in {
      val probe = createTestProbe[MqttCommand]()
      val body = envelope.replace("</SOAP-ENV:Envelope>", "<!--" + ("x" * (17 * 1024 * 1024)) + "--></SOAP-ENV:Envelope>")
      withLog(classOf[PontonRoute]) { log =>
        withServer(ponton(probe, maxBytes = BodySizeLimit.maxBytes(ConfigFactory.load()))) { base =>
          send(base.withPath(message), xml(body)) shouldBe StatusCodes.NoContent
          probe.expectMessageType[MqttPublish](10.seconds)
          logged(log(), Level.INFO, s"ponton-message-size: ${ByteString(body).length} bytes, message code SENDEN_ECP, conversation RC100181202407101720603980000000011") shouldBe true
        }
      }
    }

    "not log the size of a message up to 16 MiB" in {
      val probe = createTestProbe[MqttCommand]()
      withLog(classOf[PontonRoute]) { log =>
        withServer(ponton(probe)) { base =>
          send(base.withPath(message), xml(envelope)) shouldBe StatusCodes.NoContent
          probe.expectMessageType[MqttPublish](3.seconds)
          logged(log(), Level.INFO, "ponton-message-size") shouldBe false
        }
      }
    }
  }

  "the admin upload" should {
    "reject a body above the limit and not process it" in {
      val probe = createTestProbe[MqttCommand]()
      withServer(admin(probe)) { base =>
        send(base.withPath(adminUpload), upload("x" * (limit.toInt + 1))) shouldBe TooLarge
        probe.expectNoMessage(300.millis)
        send(base.withPath(adminUpload), upload("x" * 100)) shouldBe StatusCodes.Created
        probe.expectMessageType[MqttPublish](3.seconds)
      }
    }

    "publish none of the parts of a chunked upload that passes the limit" in {
      val probe = createTestProbe[MqttCommand]()
      withServer(admin(probe)) { base =>
        send(base.withPath(adminUpload), chunkedUpload(parts = 5, partBytes = 20 * 1024)) shouldBe TooLarge
        probe.expectNoMessage(500.millis)
        send(base.withPath(adminUpload), chunkedUpload(parts = 2, partBytes = 20 * 1024)) shouldBe StatusCodes.Created
        probe.expectMessageType[MqttPublish](3.seconds)
        probe.expectMessageType[MqttPublish](3.seconds)
      }
    }
  }

  // bound as in SupervisorActor: pontonRoutes ~ adminRoutes
  "the combined routes" should {
    "apply the Ponton limit only below /pontonxp" in {
      val probe = createTestProbe[MqttCommand]()
      withLog(classOf[PontonRoute]) { pontonLog =>
        withLog(classOf[ServiceRoute]) { adminLog =>
          withServer(ponton(probe) ~ admin(probe, maxBytes = 4 * limit)) { base =>
            // above the Ponton limit, below the admin limit
            send(base.withPath(adminUpload), upload("x" * (2 * limit.toInt))) shouldBe StatusCodes.Created
            probe.expectMessageType[MqttPublish](3.seconds)
            send(base.withPath(adminUpload), upload("x" * (5 * limit.toInt))) shouldBe TooLarge
            send(base.withPath(Uri.Path("/unknown")), xml(largeEnvelope)) shouldBe StatusCodes.NotFound
            send(base.withPath(message), xml(largeEnvelope)) shouldBe TooLarge
            pontonLog().count(_.getFormattedMessage.contains("Ponton request rejected")) shouldBe 1
            adminLog().count(_.getFormattedMessage.contains("Admin upload rejected")) shouldBe 1
          }
        }
      }
    }
  }
}
