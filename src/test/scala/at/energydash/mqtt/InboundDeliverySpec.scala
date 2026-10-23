package at.energydash.mqtt

import at.energydash.actors.MqttPublisher
import at.energydash.actors.MqttPublisher.{AggregateNotification, MqttCommand, MqttPublish}
import at.energydash.actors.http.{BodySizeLimit, PontonRoute}
import at.energydash.config.Config.MqttConfig
import at.energydash.mqtt.MqttProtocol.{MqttCmd, Terminate}
import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import io.moquette.interception.AbstractInterceptHandler
import io.moquette.interception.messages.InterceptPublishMessage
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.Await

/**
 * 261005-ca1 end to end (route, publisher, MqttSystem, embedded broker): a message MQTT cannot take
 * is answered 500 instead of 204 and dropped; a message MQTT takes is answered 204 and published
 * exactly once, also when the broker restarts while the client is connected.
 */
class InboundDeliverySpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private val envelope = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/Response-Envelope.xml"), "UTF-8").mkString
  private val soapAction = RawHeader("SOAPAction", "http://xp.ponton.de/eda/v320/inboundDocument")

  /** Embedded broker that counts the messages published by eda-xp. Ports differ per test (and from MqttBaseSpec's 18831). */
  private final class Broker(port: Int) {
    val published = new AtomicInteger()
    private val server = new Server()
    private val props = new Properties()
    props.setProperty("port", port.toString)
    props.setProperty("host", "127.0.0.1")
    props.setProperty("allow_anonymous", "true")
    props.setProperty("persistence_enabled", "false")
    server.startServer(new MemoryConfig(props), java.util.List.of(new AbstractInterceptHandler {
      override def getID: String = "count"
      override def onPublish(msg: InterceptPublishMessage): Unit = {
        if (msg.getTopicName.startsWith("eda2mqtt/")) published.incrementAndGet()
        super.onPublish(msg)
      }
      override def onSessionLoopError(error: Throwable): Unit = ()
    }))
    def stop(): Unit = server.stopServer()
  }

  /** Route, publisher (aggregator stand-in without a database) and MqttSystem against 127.0.0.1:port. */
  private def withAdapter[T](port: Int)(test: (Uri, ActorRef[MqttCmd]) => T): T = {
    val aggregator = testKit.spawn(Behaviors.receiveMessage[MqttCommand] {
      case AggregateNotification(_, n, replyTo) => replyTo ! MqttPublish(n); Behaviors.same
      case _ => Behaviors.same
    })
    val mqttSystem = testKit.spawn(MqttSystem(MqttConfig("127.0.0.1", port, "", "", ssl = false, None, None)))
    val publisher = testKit.spawn(MqttPublisher(mqttSystem, aggregator))
    val route = new PontonRoute(publisher, maxBodyBytes = BodySizeLimit.maxBytes(system.settings.config))
    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route.pontonRoutes), 5.seconds)
    try test(Uri(s"http://127.0.0.1:${binding.localAddress.getPort}/pontonxp/message"), mqttSystem)
    finally {
      mqttSystem ! Terminate
      Await.ready(binding.unbind(), 5.seconds)
    }
  }

  private def post(uri: Uri): StatusCode = {
    val res = Await.result(Http().singleRequest(HttpRequest(HttpMethods.POST, uri, soapAction :: Nil,
      HttpEntity(ContentTypes.`text/xml(UTF-8)`, envelope))), 20.seconds)
    res.discardEntityBytes()
    res.status
  }

  /** Posts until the first 204 (MqttSystem connects after its restart backoff); every earlier answer must be 500. */
  private def postUntilAccepted(uri: Uri): Unit = {
    val deadline = 90.seconds.fromNow
    var status = post(uri)
    while (status != StatusCodes.NoContent && deadline.hasTimeLeft()) {
      status shouldBe StatusCodes.InternalServerError
      Thread.sleep(1000)
      status = post(uri)
    }
    status shouldBe StatusCodes.NoContent
  }

  private def awaitCount(counter: AtomicInteger, expected: Int, within: FiniteDuration): Unit = {
    val deadline = within.fromNow
    while (counter.get() < expected && deadline.hasTimeLeft()) Thread.sleep(100)
    Thread.sleep(1000) // nothing more may follow
    counter.get() shouldBe expected
  }

  "an inbound message" should {
    "be refused while MQTT is not connected (broker down at start) and published exactly once when it is" in {
      withAdapter(18832) { (uri, _) =>
        post(uri) shouldBe StatusCodes.InternalServerError
        val broker = new Broker(18832)
        try {
          postUntilAccepted(uri)
          // only the accepted post reaches the broker, the refused ones were never published
          awaitCount(broker.published, 1, 5.seconds)
        } finally broker.stop()
      }
    }

    "be accepted while the broker restarts under a connected client and published exactly once after the reconnect" in {
      withAdapter(18833) { (uri, _) =>
        val first = new Broker(18833)
        postUntilAccepted(uri)
        awaitCount(first.published, 1, 5.seconds)
        first.stop()
        Thread.sleep(1000)
        // buffered in the stream, as before: no loss, so 204
        post(uri) shouldBe StatusCodes.NoContent
        val second = new Broker(18833)
        try awaitCount(second.published, 1, 60.seconds)
        finally second.stop()
      }
    }
  }
}
