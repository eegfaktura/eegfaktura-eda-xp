package at.energydash.mqtt

import at.energydash.actors.MqttPublisher
import at.energydash.actors.MqttPublisher.{AggregateNotification, MqttCommand, MqttPublish}
import at.energydash.actors.http.{BodySizeLimit, PontonRoute}
import at.energydash.config.Config.MqttConfig
import at.energydash.mqtt.MqttProtocol.Terminate
import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.eclipse.paho.client.mqttv3.{IMqttMessageListener, MqttClient, MqttConnectOptions}
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.Properties
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * 261005-ca1 end to end (route, publisher, MqttSystem, embedded broker): a message that cannot be
 * published while MQTT is not connected is answered 500 instead of 204 and silently dropped; once
 * the broker is up it is answered 204 and delivered exactly once.
 */
class InboundDeliverySpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  // own port: MqttBaseSpec's broker (18831) may run in parallel
  private val port = 18832
  private val envelope = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/Response-Envelope.xml"), "UTF-8").mkString
  private val soapAction = RawHeader("SOAPAction", "http://xp.ponton.de/eda/v320/inboundDocument")

  private def startBroker(): Server = {
    val props = new Properties()
    props.setProperty("port", port.toString)
    props.setProperty("host", "127.0.0.1")
    props.setProperty("allow_anonymous", "true")
    props.setProperty("persistence_enabled", "false")
    val server = new Server()
    server.startServer(new MemoryConfig(props))
    server
  }

  private def post(uri: Uri): StatusCode = {
    val res = Await.result(Http().singleRequest(HttpRequest(HttpMethods.POST, uri, soapAction :: Nil,
      HttpEntity(ContentTypes.`text/xml(UTF-8)`, envelope))), 20.seconds)
    res.discardEntityBytes()
    res.status
  }

  "an inbound message" should {
    "be refused while MQTT is not connected and delivered exactly once when it is" in {
      // aggregator stand-in without a database: hands the notifications back unchanged
      val aggregator = testKit.spawn(Behaviors.receiveMessage[MqttCommand] {
        case AggregateNotification(_, n, replyTo) => replyTo ! MqttPublish(n); Behaviors.same
        case _ => Behaviors.same
      })
      // broker down: MqttSystem cannot connect
      val mqttSystem = testKit.spawn(MqttSystem(MqttConfig("127.0.0.1", port, "", "", ssl = false, None, None)))
      val publisher = testKit.spawn(MqttPublisher(mqttSystem, aggregator))
      val route = new PontonRoute(publisher, maxBodyBytes = BodySizeLimit.maxBytes(system.settings.config), publishTimeout = 10.seconds)
      val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route.pontonRoutes), 5.seconds)
      val uri = Uri(s"http://127.0.0.1:${binding.localAddress.getPort}/pontonxp/message")
      try {
        post(uri) shouldBe StatusCodes.InternalServerError

        val broker = startBroker()
        val received = new ConcurrentLinkedQueue[String]()
        val subscriber = new MqttClient(s"tcp://127.0.0.1:$port", "inbound-delivery-spec", new MemoryPersistence)
        try {
          subscriber.connect(new MqttConnectOptions())
          subscriber.subscribe("eda2mqtt/#", 1, new IMqttMessageListener {
            override def messageArrived(topic: String, message: org.eclipse.paho.client.mqttv3.MqttMessage): Unit = received.add(topic)
          })
          // MqttSystem reconnects after its restart backoff (4 s, growing); until then every post is refused
          val deadline = 90.seconds.fromNow
          var status = post(uri)
          while (status != StatusCodes.NoContent && deadline.hasTimeLeft()) {
            status shouldBe StatusCodes.InternalServerError
            received.isEmpty shouldBe true
            Thread.sleep(1000)
            status = post(uri)
          }
          status shouldBe StatusCodes.NoContent
          // confirmed by the broker before the 204, so it reaches the subscriber, and only the accepted post does
          val arrival = 5.seconds.fromNow
          while (received.isEmpty && arrival.hasTimeLeft()) Thread.sleep(100)
          Thread.sleep(1000)
          received.size shouldBe 1
          received.peek() should startWith("eda2mqtt/rc100181/protocol/")
        } finally {
          if (subscriber.isConnected) subscriber.disconnect()
          subscriber.close()
          mqttSystem ! Terminate
          broker.stopServer()
        }
      } finally Await.ready(binding.unbind(), 5.seconds)
    }
  }
}
