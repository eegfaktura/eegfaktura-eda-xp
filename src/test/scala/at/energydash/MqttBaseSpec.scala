package at.energydash

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.connectors.mqtt.MqttConnectionSettings
import org.apache.pekko.util.ByteString
import at.energydash.mqtt.MqttSourceSettings
import io.moquette.broker.Server
import io.moquette.broker.config.{FileResourceLoader, ResourceLoaderConfig}
import io.netty.buffer.Unpooled
import io.netty.handler.codec.mqtt.{MqttMessageBuilders, MqttQoS}
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

import java.io.File

trait Sepp {

}

trait MqttBaseSpec extends ScalaTestWithActorTestKit {
  import MqttSourceSpec._

  val connectionSettings = MqttConnectionSettings(
    "tcp://localhost:18831",
    "test-client",
    new MemoryPersistence
  )

  def publish(topic: String, payload: String)(implicit server: Server) = {
    val msg =
      MqttMessageBuilders.publish.topicName(topic)
        .retained(false)
        .qos(MqttQoS.AT_MOST_ONCE)
        .payload(Unpooled.copiedBuffer(ByteString(payload).toByteBuffer)).build
    server.internalPublish(msg, "INTRLPUB")
  }

  def withBroker(subscriptions: Map[String, Int], serverAuth: Option[(String, String)] = None)(test: FixtureParam => Any) = {
    val mat: Materializer = Materializer(system)

    val settings = MqttSourceSettings(
      connectionSettings,
      subscriptions
    )

    val mqttServer = new Server()
//    val authenticator = new IAuthenticator {
//      override def checkValid(username: String, password: Array[Byte]): Boolean =
//        serverAuth.fold(true) { case (u, p) => username == u && new String(password) == p }
//    }
    val filePathLoader = new FileResourceLoader(new File(getClass.getResource("/moquette.conf").getPath))
    val classPathConfig = new ResourceLoaderConfig(filePathLoader);

    mqttServer.startServer(classPathConfig, null, null, null, null)
    try {
      test(FixtureParam(settings, mqttServer, system, mat))
    } finally {
      mqttServer.stopServer()
    }

//    Await.ready(system.whenTerminated, 5.seconds)
  }

//  def withClientAuth(settings: MqttSourceSettings, auth: (String, String)): MqttSourceSettings =
//    settings.copy(connectionSettings = settings.connectionSettings.copy(auth = Some(auth)))

}

object MqttSourceSpec {

  def fixture(p: FixtureParam) = new {
    implicit val mqttServer = p.mqttServer
    implicit val system = p.sys
    implicit val materializer = p.mat
  }

  case class FixtureParam(settings: MqttSourceSettings, mqttServer: Server, sys: ActorSystem[_], mat: Materializer)

}