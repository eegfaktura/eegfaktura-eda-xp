package at.energydash.robustness

import at.energydash.EmbeddedDb
import at.energydash.actors.ConversationEntity.{InitConversation, InitDone}
import at.energydash.actors.MqttPublisher.MqttCommand
import at.energydash.actors.PrepareMessageActor.PrepareMessage
import at.energydash.actors.TenantProvider.TenantStart
import at.energydash.actors._
import at.energydash.admin.RegisterPontonRequest
import at.energydash.domain.EbMsMessage
import at.energydash.domain.dao.TenantConfig
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.service.AdminServiceImpl
import at.energydash.stream.MqttRequestStream
import at.energydash.testsupport.KnownError.knownError
import at.energydash.testsupport.{FakePonton, TestBroker, TestDb, Tenants}
import io.circe.parser.parse
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.connectors.mqtt.scaladsl.MqttSource
import org.apache.pekko.stream.connectors.mqtt.{MqttConnectionSettings, MqttMessage, MqttQoS, MqttSubscriptions}
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.apache.pekko.util.ByteString
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration._

/** M5: failure modes on actor and stream level (rows R1, R2, R4, R6, R7, R10, R11 of m05-robustness.md). */
class RobustnessUnitSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with EmbeddedDb with BeforeAndAfterEach with Eventually {
  private lazy val ponton = new FakePonton()(system)
  override def beforeEach(): Unit = { TestDb.reset(); TestDb.seedTenant("RC100401", "KEP"); ponton.reset() }
  override def afterAll(): Unit = { ponton.stop(); super.afterAll() }

  private val ecp = EbMsMessage(messageId = Some("RC100401209901010000000000000001"), conversationId = "C1",
    sender = "RC100401", receiver = "AT003000", messageCode = EbMsMessageType.ZP_LIST, messageCodeVersion = Some("02.10"),
    requestId = Some("R1"))

  "R1: a KEP request during the tenant initialisation" should {
    "be sent once the initialisation is done" in {
      val provider = spawn(TenantProvider(createTestProbe[MqttCommand]().ref))
      val reply = createTestProbe[EdaCommand]()
      provider ! TenantStart
      provider ! PassEdaCommand("RC100401", ecp, reply.ref)
      reply.expectMessage(3.seconds, SendEdaResponse(ecp))
    }
  }

  "R10: a request after a re-initialisation (TenantStart while running)" should {
    "be sent" in {
      val provider = spawn(TenantProvider(createTestProbe[MqttCommand]().ref))
      provider ! TenantStart
      Tenants.awaitReady(provider, testKit)
      val reply = createTestProbe[EdaCommand]()
      provider ! TenantStart
      provider ! PassEdaCommand("RC100401", ecp, reply.ref)
      reply.expectMessage(3.seconds, SendEdaResponse(ecp))
    }
  }

  "R4: a second update of a non-KEP tenant" should {
    "keep the provider alive and answer ResponseOk [261005-ca3]" taggedAs Slow in {
      val provider = spawn(TenantProvider(createTestProbe[MqttCommand]().ref))
      provider ! TenantStart
      Tenants.awaitReady(provider, testKit)
      val mail = TenantConfig("RC100409", "MAIL", Some("email.com"), Some("email.com"), Some(143), Some("email.com"), Some(25),
        Some("u"), Some("p"), Some("STARTTLS"), Some("STARTTLS"), active = true)
      val reply = createTestProbe[EdaCommand]()
      provider ! UpdateTenant(mail, reply.ref)
      reply.expectMessageType[ResponseOk](3.seconds)
      provider ! UpdateTenant(mail, reply.ref)
      reply.expectMessageType[ResponseOk](3.seconds)
      Tenants.awaitReady(provider, testKit) // still answering
    }
  }

  "R10: a re-initialisation with a MAIL tenant" should {
    "keep the provider alive [261005-ca3]" taggedAs Slow in {
      TestDb.seedTenant("RC100409", "MAIL")
      val provider = spawn(TenantProvider(createTestProbe[MqttCommand]().ref))
      provider ! TenantStart
      Tenants.awaitReady(provider, testKit)
      provider ! TenantStart // the reload spawns a new worker for RC100409 while the first one may still run
      Tenants.awaitReady(provider, testKit)
    }
  }

  "R12: a request while the supervisor restarts the provider" should {
    "be answered after the reload [261005-ca3]" taggedAs Slow in {
      val provider = spawn(TenantProvider.supervised(createTestProbe[MqttCommand]().ref)) // starts itself
      Tenants.awaitReady(provider, testKit)
      // a tenant name that is not a valid actor name: spawning its worker on the update fails the provider
      val bad = TenantConfig("RC/BAD", "MAIL", Some("email.com"), Some("email.com"), Some(143), Some("email.com"), Some(25),
        Some("u"), Some("p"), Some("STARTTLS"), Some("STARTTLS"), active = true)
      val added = createTestProbe[EdaCommand]()
      provider ! AddTenant(bad, added.ref)
      added.expectMessageType[ResponseOk](3.seconds)
      val reply = createTestProbe[EdaCommand]()
      provider ! PassEdaCommand("not-a-tenant", ecp.copy(sender = "not-a-tenant"), reply.ref)
      reply.expectMessageType[SendResponseError](10.seconds) // restart backoff 1 s, then the reload
    }
  }

  "R6: the database fails when a conversation is stored" should {
    "answer an error, not InitDone [261005-ca7]" in knownError("261005-ca7") {
      val entity = spawn(ConversationEntity())
      val reply = createTestProbe[EdaCommand]()
      TestDb.exec("ALTER TABLE eda.conversation RENAME TO conversation_away")
      try {
        entity ! InitConversation(ecp, reply.ref)
        reply.receiveMessage(3.seconds) should not be InitDone(ecp)
      } finally TestDb.exec("ALTER TABLE eda.conversation_away RENAME TO conversation")
    }
  }

  "R11: the database fails during a gRPC register" should {
    "answer status 500 with the cause (pinned)" in {
      val provider = spawn(TenantProvider(createTestProbe[MqttCommand]().ref))
      provider ! TenantStart
      Tenants.awaitReady(provider, testKit)
      TestDb.exec("ALTER TABLE eda.tenantconfig RENAME TO tenantconfig_away")
      try {
        val reply = Await.result(new AdminServiceImpl(provider)(system.scheduler, system.executionContext)
          .register(RegisterPontonRequest("RC100410", "u", "p", "email.com", "KEP")), 15.seconds)
        reply.status shouldBe 500
        reply.message should not be empty
      } finally TestDb.exec("ALTER TABLE eda.tenantconfig_away RENAME TO tenantconfig")
    }
  }

  "R7: the request stream" should {
    def stream(prepare: org.apache.pekko.actor.typed.ActorRef[PrepareMessageActor.Command[PrepareMessageActor.PrepareMessageResult]]) =
      new MqttRequestStream(createTestProbe[EdaCommand]().ref, prepare, createTestProbe[EdaCommand]().ref)(system)
    def run(s: MqttRequestStream, payloads: String*) = {
      val (probe, sink) = TestSink[MqttMessage]()(system.classicSystem).preMaterialize()(org.apache.pekko.stream.Materializer(system))
      s.runCommand(Source(payloads.toList.map(p => MqttMessage("eda/request", ByteString(p)))).mapMaterializedValue(_ => scala.concurrent.Future.never), sink)
      probe.request(10) // demand first: without it the stream does not pull
      probe
    }

    "answer malformed JSON and an unknown message code with an error JSON and stay alive (pinned)" in {
      val prepare = createTestProbe[PrepareMessageActor.Command[PrepareMessageActor.PrepareMessageResult]]()
      val probe = run(stream(prepare.ref), "{not json", """{"conversationId":"C","sender":"S","receiver":"R","messageCode":"ANFORDERUNG_XYZ"}""")
      val first = probe.expectNext(5.seconds); val second = probe.expectNext(5.seconds)
      Seq(first, second).map(_.topic).distinct shouldBe Seq("eda/response/error")
      Seq(first, second).foreach(m => parse(m.payload.utf8String).isRight shouldBe true)
    }

    "publish a JSON error when a stage times out [261005-ca16]" in knownError("261005-ca16") {
      val prepare = createTestProbe[PrepareMessageActor.Command[PrepareMessageActor.PrepareMessageResult]]() // never answers
      val probe = run(stream(prepare.ref), """{"conversationId":"C","sender":"RC100401","receiver":"AT003000","messageCode":"ANFORDERUNG_ECP"}""")
      prepare.expectMessageType[PrepareMessage](3.seconds)
      val m = probe.expectNext(6.seconds)
      parse(m.payload.utf8String).isRight shouldBe true
    }
  }

  "R2 control: the broker is there when the request stream starts" should {
    "process a request (proves the R2 set-up)" in {
      TestBroker.withBroker { _ =>
        val prepare = createTestProbe[PrepareMessageActor.Command[PrepareMessageActor.PrepareMessageResult]]()
        val settings = MqttConnectionSettings(TestBroker.Url, s"r2c-${System.nanoTime()}", new MemoryPersistence)
        // the stream restarts its source: without the switch it would subscribe again on every later test broker
        val stop = KillSwitches.shared("r2c")
        val subscribed = new MqttRequestStream(createTestProbe[EdaCommand]().ref, prepare.ref, createTestProbe[EdaCommand]().ref)(system)
          .runCommand(MqttSource.atMostOnce(settings, MqttSubscriptions("eda/request", MqttQoS.AtLeastOnce), 10).via(stop.flow),
            org.apache.pekko.stream.scaladsl.Sink.ignore)
        val client = new at.energydash.testsupport.MqttRecorder("none")
        try {
          Await.result(subscribed, 10.seconds)
          client.publish("eda/request", """{"conversationId":"C","sender":"RC100401","receiver":"AT003000","messageCode":"ANFORDERUNG_ECP"}""")
          prepare.expectMessageType[PrepareMessage](3.seconds)
          stop.shutdown() // the stream ends and is not restarted: nothing is taken any more
          client.publish("eda/request", """{"conversationId":"C","sender":"RC100401","receiver":"AT003000","messageCode":"ANFORDERUNG_ECP"}""")
          prepare.expectNoMessage(1.second)
        } finally { stop.shutdown(); client.close() }
      }
    }
  }

  "R2: the broker is absent when the request stream starts" should {
    "process requests once the broker is up [261005-ca2]" taggedAs Slow in {
      val prepare = createTestProbe[PrepareMessageActor.Command[PrepareMessageActor.PrepareMessageResult]]()
      val settings = MqttConnectionSettings(TestBroker.Url, s"r2-${System.nanoTime()}", new MemoryPersistence)
        .withAutomaticReconnect(true).withCleanSession(false)
      val sink = org.apache.pekko.stream.scaladsl.Sink.ignore // unlimited demand
      val stop = KillSwitches.shared("r2") // ends the restarting stream with the case
      try {
        val subscribed = new MqttRequestStream(createTestProbe[EdaCommand]().ref, prepare.ref, createTestProbe[EdaCommand]().ref)(system)
          .runCommand(MqttSource.atMostOnce(settings, MqttSubscriptions("eda/request", MqttQoS.AtLeastOnce), 10).via(stop.flow), sink)
        Await.ready(subscribed, 10.seconds) // no broker: the subscription fails
        TestBroker.withBroker { _ =>
          val client = new at.energydash.testsupport.MqttRecorder("none")
          try {
            // the stream subscribes on its next restart (backoff): a request published before that is not
            // delivered (no session on the new broker), so publish until one is processed
            eventually(timeout(10.seconds), interval(100.millis)) {
              client.publish("eda/request", """{"conversationId":"C","sender":"RC100401","receiver":"AT003000","messageCode":"ANFORDERUNG_ECP"}""")
              prepare.expectMessageType[PrepareMessage](500.millis)
            }
          } finally client.close()
        }
      } finally stop.shutdown()
    }

    "publish answers once the broker is up, through an MQTT response sink [261005-ca2]" taggedAs Slow in {
      val prepare = createTestProbe[PrepareMessageActor.Command[PrepareMessageActor.PrepareMessageResult]]()
      val settings = MqttConnectionSettings(TestBroker.Url, s"r2s-${System.nanoTime()}", new MemoryPersistence)
        .withAutomaticReconnect(true).withCleanSession(false)
      val sink = org.apache.pekko.stream.connectors.mqtt.scaladsl.MqttSink(settings.withClientId(s"${settings.clientId}/pong"), MqttQoS.AtLeastOnce)
      val stop = KillSwitches.shared("r2s") // ends the restarting stream (and with it the sink) with the case
      try {
        val subscribed = new MqttRequestStream(createTestProbe[EdaCommand]().ref, prepare.ref, createTestProbe[EdaCommand]().ref)(system)
          .runCommand(MqttSource.atMostOnce(settings, MqttSubscriptions("eda/request", MqttQoS.AtLeastOnce), 10).via(stop.flow), sink)
        Await.ready(subscribed, 10.seconds) // no broker: source and sink fail
        TestBroker.withBroker { _ =>
          val client = new at.energydash.testsupport.MqttRecorder("eda/response/#")
          try {
            // malformed JSON is answered with an error JSON on eda/response/error, through the sink
            eventually(timeout(10.seconds), interval(100.millis)) {
              client.publish("eda/request", "{not json")
              client.next(500.millis).map(_.topic) shouldBe Some("eda/response/error")
            }
          } finally client.close()
        }
      } finally stop.shutdown()
    }
  }
}
