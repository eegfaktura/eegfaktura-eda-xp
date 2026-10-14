package at.energydash.scenario

import at.energydash.actors.TenantProvider.TenantStart
import at.energydash.actors._
import at.energydash.actors.http.{PontonRoute, ServiceRoute}
import at.energydash.config.Config
import at.energydash.mqtt.MqttSystem
import at.energydash.service.FileService
import at.energydash.stream.MqttRequestStream
import at.energydash.testsupport.{TestBroker, Tenants}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.connectors.mqtt.scaladsl.{MqttSink, MqttSource}
import org.apache.pekko.stream.connectors.mqtt.{MqttConnectionSettings, MqttQoS, MqttSubscriptions}
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * The actor graph of `SupervisorActor.apply`, built in its own actor system with its own journal directory:
 * conversation store, aggregator, `MqttSystem` → the test broker, publisher, `PrepareMessageActor`,
 * `TenantProvider` on the test database, the MQTT request stream and the HTTP routes (ephemeral loopback
 * port). Differences to `SupervisorActor`, all for test isolation: the routes bind port 0, the request
 * stream runs through `runCommand` with the companion's settings but its own client ids, and no gRPC server.
 * Tenants must be in `eda.tenantconfig` before `start` (the provider reads them once); `start` waits for
 * the provider's initialisation (261005-ca4).
 */
final class ScenarioGraph(name: String, journal: String) {
  // The native LevelDB journal does not create missing parent directories.
  // (a test that wants an unusable journal puts a file where the directory would be: creation then fails here)
  Seq("journal", "snapshots").foreach(d => scala.util.Try(java.nio.file.Files.createDirectories(java.nio.file.Paths.get(journal, d))))
  val kit: ActorTestKit = ActorTestKit(name, ConfigFactory.parseString(
    s"""pekko.persistence.journal.leveldb.dir = "$journal/journal"
       |pekko.persistence.snapshot-store.local.dir = "$journal/snapshots"""".stripMargin)
    .withFallback(ActorTestKit.ApplicationTestConfig))
  private implicit val system: org.apache.pekko.actor.typed.ActorSystem[Nothing] = kit.system

  val conversations = kit.spawn(ConversationEntity(), "conversationentity")
  private val aggregator = kit.spawn(EbMsAggregator(conversations), "ebMsAggregator")
  private val mqtt = kit.spawn(MqttSystem(Config.getMqttConfig()), "mqtt-system")
  val publisher = kit.spawn(MqttPublisher(mqtt, aggregator), "mqtt-publisher")
  private val prepare = kit.spawn(PrepareMessageActor(), "message-transformer")
  val tenants = kit.spawn(TenantProvider(publisher), "tenant-provider")

  private val client = s"scenario-$name-${System.nanoTime()}"
  private val settings = MqttConnectionSettings(TestBroker.Url, client, new MemoryPersistence)
    .withAutomaticReconnect(true).withCleanSession(false)

  private val routes = new PontonRoute(publisher).pontonRoutes ~
    new ServiceRoute(FileService(system, publisher)(Materializer(system))).adminRoutes
  private val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(routes), 10.seconds)
  val baseUrl: String = s"http://127.0.0.1:${binding.localAddress.getPort}"

  def start(): this.type = {
    tenants ! TenantStart
    Tenants.awaitReady(tenants, kit)
    val stream = MqttRequestStream(tenants, prepare, conversations)
    val subscribed = stream.runCommand(
      MqttSource.atMostOnce(settings, MqttSubscriptions(Map(Config.getMqttMailConfig.topic -> MqttQoS.AtLeastOnce)), bufferSize = 10),
      MqttSink(settings.withClientId(s"$client/pong"), MqttQoS.atLeastOnce))
    Await.result(subscribed, 10.seconds)
    this
  }

  def stop(): Unit = { Await.ready(binding.unbind(), 10.seconds); kit.shutdownTestKit() }
}
