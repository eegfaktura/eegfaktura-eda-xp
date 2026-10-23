package at.energydash.actors.http

import at.energydash.actors.MqttPublisher.{MqttCommand, MqttPublish}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import ch.qos.logback.core.read.ListAppender
import com.typesafe.config.ConfigFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._

// 261005-ca1: an inbound message is answered 204 only after the broker confirmed its publish
class PontonRouteAckSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  private val envelope = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/Response-Envelope.xml"), "UTF-8").mkString
  private val soapAction = RawHeader("SOAPAction", "http://xp.ponton.de/eda/v320/inboundDocument")

  private def withRoute[T](probe: TestProbe[MqttCommand], publishTimeout: FiniteDuration)(test: Uri => T): T = {
    val route = new PontonRoute(probe.ref, maxBodyBytes = BodySizeLimit.maxBytes(system.settings.config), publishTimeout = publishTimeout)
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route.pontonRoutes), 5.seconds)
    try test(Uri(s"http://127.0.0.1:${binding.localAddress.getPort}/pontonxp/message"))
    finally Await.ready(binding.unbind(), 5.seconds)
  }

  private def post(uri: Uri): Future[StatusCode] =
    Http().singleRequest(HttpRequest(HttpMethods.POST, uri, soapAction :: Nil, HttpEntity(ContentTypes.`text/xml(UTF-8)`, envelope)))
      .map { res => res.discardEntityBytes(); res.status }

  private def withLog[T](test: (() => List[ILoggingEvent]) => T): T = {
    val logger = LoggerFactory.getLogger(classOf[PontonRoute]).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    try test(() => appender.synchronized(appender.list.asScala.toList))
    finally logger.detachAppender(appender)
  }

  "the message route" should {
    "answer 204 only after the broker confirmed the publish" in {
      val probe = createTestProbe[MqttCommand]()
      withRoute(probe, 10.seconds) { uri =>
        val status = post(uri)
        val publish = probe.expectMessageType[MqttPublish](5.seconds)
        publish.mails.map(_.message.conversationId) shouldBe List("RC100181202407101720603980000000011")
        Thread.sleep(500)
        status.isCompleted shouldBe false
        publish.delivered.get.success(Done)
        Await.result(status, 5.seconds) shouldBe StatusCodes.NoContent
      }
    }

    "answer 500 when the broker does not confirm within the timeout" in withLog { log =>
      val probe = createTestProbe[MqttCommand]()
      withRoute(probe, 1.second) { uri =>
        val status = post(uri)
        val publish = probe.expectMessageType[MqttPublish](5.seconds)
        Await.result(status, 5.seconds) shouldBe StatusCodes.InternalServerError
        // a late confirmation changes nothing
        publish.delivered.get.trySuccess(Done) shouldBe false
        log().exists(e => e.getLevel == Level.ERROR &&
          e.getFormattedMessage.contains("conversation RC100181202407101720603980000000011") &&
          e.getFormattedMessage.contains("no broker confirmation within 1 second")) shouldBe true
      }
    }

    "answer 500 at once when the publisher reports the message as not published" in withLog { log =>
      val probe = createTestProbe[MqttCommand]()
      withRoute(probe, 10.seconds) { uri =>
        val started = System.nanoTime()
        val status = post(uri)
        probe.expectMessageType[MqttPublish](5.seconds).delivered.get.failure(new IllegalStateException("MQTT client not connected"))
        Await.result(status, 5.seconds) shouldBe StatusCodes.InternalServerError
        (System.nanoTime() - started).nanos should be < 5.seconds
        log().exists(e => e.getLevel == Level.ERROR && e.getFormattedMessage.contains("MQTT client not connected; Ponton will redeliver")) shouldBe true
      }
    }
  }

  "app.inbound-publish-timeout" should {
    "default to 10 s and refuse values below 1 s" in {
      PontonRoute.publishTimeout(ConfigFactory.load()) shouldBe 10.seconds
      PontonRoute.publishTimeout(ConfigFactory.parseString("app.inbound-publish-timeout = 3s")) shouldBe 3.seconds
      an[IllegalArgumentException] should be thrownBy PontonRoute.publishTimeout(ConfigFactory.parseString("app.inbound-publish-timeout = 10"))
    }
  }
}
