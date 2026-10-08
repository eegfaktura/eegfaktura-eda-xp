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
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.apache.pekko.util.ByteString
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration._

/** M5: failure modes on actor and stream level (rows R1, R2, R4, R6, R7, R10, R11 of m05-robustness.md). */
class RobustnessUnitSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with EmbeddedDb with BeforeAndAfterEach {
  private lazy val ponton = new FakePonton()(system)
  override def beforeEach(): Unit = { TestDb.reset(); TestDb.seedTenant("RC100401", "KEP"); ponton.reset() }
  override def afterAll(): Unit = { ponton.stop(); super.afterAll() }

  private val ecp = EbMsMessage(messageId = Some("RC100401209901010000000000000001"), conversationId = "C1",
    sender = "RC100401", receiver = "AT003000", messageCode = EbMsMessageType.ZP_LIST, messageCodeVersion = Some("02.10"),
    requestId = Some("R1"))

  "R1: a KEP request during the tenant initialisation" should {
    "be sent once the initialisation is done [261005-ca4]" in knownError("261005-ca4") {
      val provider = spawn(TenantProvider(createTestProbe[MqttCommand]().ref))
      val reply = createTestProbe[EdaCommand]()
      provider ! TenantStart
      provider ! PassEdaCommand("RC100401", ecp, reply.ref)
      reply.expectMessage(3.seconds, SendEdaResponse(ecp))
    }
  }

  "R10: a request after a re-initialisation (TenantStart while running)" should {
    "be sent [261005-ca4]" in knownError("261005-ca4") {
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
    "keep the provider alive and answer ResponseOk [261005-ca3]" in knownError("261005-ca3") {
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
        val subscribed = new MqttRequestStream(createTestProbe[EdaCommand]().ref, prepare.ref, createTestProbe[EdaCommand]().ref)(system)
          .runCommand(MqttSource.atMostOnce(settings, MqttSubscriptions("eda/request", MqttQoS.AtLeastOnce), 10),
            org.apache.pekko.stream.scaladsl.Sink.ignore)
        Await.result(subscribed, 10.seconds)
        val client = new at.energydash.testsupport.MqttRecorder("none")
        try {
          client.publish("eda/request", """{"conversationId":"C","sender":"RC100401","receiver":"AT003000","messageCode":"ANFORDERUNG_ECP"}""")
          prepare.expectMessageType[PrepareMessage](3.seconds)
        } finally client.close()
      }
    }
  }

  "R2: the broker is absent when the request stream starts" should {
    "process requests once the broker is up [261005-ca2]" in knownError("261005-ca2") {
      val prepare = createTestProbe[PrepareMessageActor.Command[PrepareMessageActor.PrepareMessageResult]]()
      val settings = MqttConnectionSettings(TestBroker.Url, s"r2-${System.nanoTime()}", new MemoryPersistence)
        .withAutomaticReconnect(true).withCleanSession(false)
      val sink = org.apache.pekko.stream.scaladsl.Sink.ignore // unlimited demand
      val subscribed = new MqttRequestStream(createTestProbe[EdaCommand]().ref, prepare.ref, createTestProbe[EdaCommand]().ref)(system)
        .runCommand(MqttSource.atMostOnce(settings, MqttSubscriptions("eda/request", MqttQoS.AtLeastOnce), 10), sink)
      Await.ready(subscribed, 10.seconds) // no broker: the subscription fails
      TestBroker.withBroker { _ =>
        val client = new at.energydash.testsupport.MqttRecorder("none")
        try {
          client.publish("eda/request", """{"conversationId":"C","sender":"RC100401","receiver":"AT003000","messageCode":"ANFORDERUNG_ECP"}""")
          prepare.expectMessageType[PrepareMessage](3.seconds)
        } finally client.close()
      }
    }
  }
}
