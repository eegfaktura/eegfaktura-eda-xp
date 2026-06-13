package at.energydash.stream

import org.apache.pekko.Done
import org.apache.pekko.stream.connectors.mqtt.scaladsl.MqttSource
import org.apache.pekko.stream.connectors.mqtt.{MqttConnectionSettings, MqttMessage, MqttQoS, MqttSubscriptions}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import at.energydash.MqttSourceSpec.fixture
import at.energydash.actors.ConversationEntity.{InitConversation, InitDone}
import at.energydash.actors.PrepareMessageActor.{PrepareMessage, Prepared}
import at.energydash.actors._
import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.{EmbeddedDb, MqttBaseSpec}
import io.circe.generic.auto._
import io.circe.syntax.{EncoderOps, _}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Future

class MqttRequestStreamSpec extends MqttBaseSpec with EmbeddedDb with AnyWordSpecLike {

  val sourceSettings: MqttConnectionSettings = connectionSettings.withClientId(clientId = "source-spec/source")
  val sinkSettings: MqttConnectionSettings = connectionSettings.withClientId(clientId = "source-spec/sink")

  "MQTT Stream" should {
    "Handle Mqtt message" in withBroker(Map("topic1" -> 0)) { p =>
      val f = fixture(p)
      import at.energydash.domain.JsonImplicit._
      import f._
      import f.system._

      val testMsg = """{"conversationId":"RC100699202407221900383040000107598","messageId":"RC100699202407221900383040000107597","sender":"myeeg-kep","receiver":"AT003100","messageCode":"ANFORDERUNG_ECON","messageCodeVersion":"02.00","requestId":"48NaALA","meter":{"meteringPoint":"AT0031000000099000000000000005832","direction":"GENERATION","partFact":100},"ecId":"AT00310000000RC100699EGR000600001"}"""
      val topic = "source-spec/manualacks"
      val mqttSource: Source[MqttMessage, Future[Done]] =
        MqttSource.atMostOnce(
          connectionSettings
            .withClientId(clientId = "source-spec/source1")
            .withCleanSession(false),
          MqttSubscriptions(topic, MqttQoS.AtLeastOnce),
          bufferSize = 8
        )
      val edaActorProbe = createTestProbe[EdaCommand]()
      val transformerActorProbe = createTestProbe[PrepareMessageActor.Command[PrepareMessageActor.PrepareMessageResult]]()
      val storeActorProbe = createTestProbe[EdaCommand]()

      val stream = new MqttRequestStream(edaActorProbe.ref, transformerActorProbe.ref, storeActorProbe.ref)(f.system)
      val (probe, sink) = TestSink[MqttMessage]()(f.system.classicSystem).preMaterialize()
      val ready = stream.runCommand(mqttSource, sink)
      whenReady(ready) { _ =>
        publish(topic, testMsg)

        probe.request(1)
        val msg = transformerActorProbe.expectMessageType[PrepareMessage]
        msg.replyTo ! Prepared(msg.message)

        val edaCommand = edaActorProbe.expectMessageType[PassEdaCommand]
        edaCommand.replyTo ! SendEdaResponse(edaCommand.message)

        val s = storeActorProbe.expectMessageType[InitConversation]
        s.replyTo ! InitDone(s.message)
        println("finish")

        probe.requestNext().topic shouldBe "eda/response/myeeg-kep/protocol/ec_req_onl"
      }
    }

    "Handle Mqtt message for TYPE EMAIL" in withBroker(Map("topic1" -> 0)) { p =>
      val f = fixture(p)
      import at.energydash.domain.JsonImplicit._
      import f._
      import f.system._

      val testMsg = """{"conversationId":"RC100699202407221900383040000107598","messageId":"RC100699202407221900383040000107597","sender":"myeeg","receiver":"AT003100","messageCode":"ANFORDERUNG_ECON","messageCodeVersion":"02.00","requestId":"48NaALA","meter":{"meteringPoint":"AT0031000000099000000000000005832","direction":"GENERATION","partFact":100},"ecId":"AT00310000000RC100699EGR000600001"}"""
      val topic = "source-spec/manualacks"
//      val input = Vector("one", "two", "three", "four", "five")
      val mqttSource: Source[MqttMessage, Future[Done]] =
        MqttSource.atMostOnce(
          connectionSettings
            .withClientId(clientId = "source-spec/source1")
            .withCleanSession(false),
          MqttSubscriptions(topic, MqttQoS.AtLeastOnce),
          bufferSize = 8
        )
      val edaActorProbe = createTestProbe[EdaCommand]()
      val transformerActorProbe = createTestProbe[PrepareMessageActor.Command[PrepareMessageActor.PrepareMessageResult]]()
      val storeActorProbe = createTestProbe[EdaCommand]()

      val stream = new MqttRequestStream(edaActorProbe.ref, transformerActorProbe.ref, storeActorProbe.ref)(f.system)
      val (probe, sink) = TestSink[MqttMessage]()(f.system.classicSystem).preMaterialize()
      val ready = stream.runCommand(mqttSource, sink)
      whenReady(ready) { _ =>
        publish(topic, testMsg)

        probe.request(1)
//        transformerActorProbe.expectNoMessage(2.seconds)
        val msg = transformerActorProbe.expectMessageType[PrepareMessage]
        msg.replyTo ! Prepared(msg.message)

        val edaCommand = edaActorProbe.expectMessageType[PassEdaCommand]
        edaCommand.replyTo ! SendEdaResponse(edaCommand.message)

        val s = storeActorProbe.expectMessageType[InitConversation]
        s.replyTo ! InitDone(s.message)
        println("finish")

        probe.requestNext().topic shouldBe "eda/response/myeeg/protocol/ec_req_onl"

      }
    }

    "Handle malformed Mqtt message" in withBroker(Map("topic1" -> 0)) { p =>
      val f = fixture(p)
      import at.energydash.domain.JsonImplicit._
      import f._
      import f.system._

      val testMsg = """{"conversationId":"RC100699202407221900383040000107598","messageId":"RC100699202407221900383040000107597","sender":"myeeg","receiver":"netz linz","messageCode":"ANFORDERUNG_ECON","messageCodeVersion":"02.00","requestId":"48NaALA","meter":{"meteringPoint":"AT0031000000099000000000000005832","direction":"GENERATION","partFact":100},"ecId":"AT00310000000RC100699EGR000600001"}"""
      val topic = "source-spec/manualacks"
//      val input = Vector("one", "two", "three", "four", "five")
      val mqttSource: Source[MqttMessage, Future[Done]] =
        MqttSource.atMostOnce(
          connectionSettings
            .withClientId(clientId = "source-spec/source1")
            .withCleanSession(false),
          MqttSubscriptions(topic, MqttQoS.AtLeastOnce),
          bufferSize = 8
        )
      val edaActorProbe = createTestProbe[EdaCommand]()
      val transformerActorProbe = createTestProbe[PrepareMessageActor.Command[PrepareMessageActor.PrepareMessageResult]]()
      val storeActorProbe = createTestProbe[EdaCommand]()

      val stream = new MqttRequestStream(edaActorProbe.ref, transformerActorProbe.ref, storeActorProbe.ref)(f.system)
      val (probe, sink) = TestSink[MqttMessage]()(f.system.classicSystem).preMaterialize()
      val ready = stream.runCommand(mqttSource, sink)
      whenReady(ready) { _ =>
        publish(topic, testMsg)

        probe.request(1)
        val msg = transformerActorProbe.expectMessageType[PrepareMessage]
        msg.replyTo ! Prepared(msg.message)

        val edaCommand = edaActorProbe.expectMessageType[PassEdaCommand]
        edaCommand.replyTo ! SendResponseError("myeeg", "netz linz", "Local address contains control or whitespace", "Send Mail")

        println("finish")

        val expectedPayload = EbMsMessage(
          conversationId = "0", sender = "myeeg", receiver = "netz linz",
          messageCode = EbMsMessageType.ERROR_MESSAGE,
          errorMessage = Some("Local address contains control or whitespace"),
          reason=Some("Send Mail")).asJson.toString

        val testResponse = probe.requestNext()
        testResponse.topic shouldBe "eda/response/myeeg/protocol/error"
        testResponse.payload.utf8String shouldBe expectedPayload
      }
    }

    "Handle malformed EDA message" in withBroker(Map("topic1" -> 0)) { p =>
      val f = fixture(p)
      import at.energydash.domain.JsonImplicit._
      import f._
      import f.system._

      val testMsg = """{"conversationId":"","sender":"RC103536","receiver":"AT003000","messageCode":"ANFORDERUNG_PT","messageCodeVersion":"03.00","meter":{"meteringPoint":"AT0030000000000000000000000526178"},"ecId":"AT00300000000RC103536000000973930","timeline":{"from":1732748400000,"to":1732833900000}}"""
      val topic = "source-spec/manualacks"
      //      val input = Vector("one", "two", "three", "four", "five")
      val mqttSource: Source[MqttMessage, Future[Done]] =
        MqttSource.atMostOnce(
          connectionSettings
            .withClientId(clientId = "source-spec/source1")
            .withCleanSession(false),
          MqttSubscriptions(topic, MqttQoS.AtLeastOnce),
          bufferSize = 8
        )
      val edaActorProbe = createTestProbe[EdaCommand]()
      val transformerActorProbe = createTestProbe[PrepareMessageActor.Command[PrepareMessageActor.PrepareMessageResult]]()
      val storeActorProbe = createTestProbe[EdaCommand]()

      val stream = new MqttRequestStream(edaActorProbe.ref, transformerActorProbe.ref, storeActorProbe.ref)(f.system)
      val (probe, sink) = TestSink[MqttMessage]()(f.system.classicSystem).preMaterialize()
      val ready = stream.runCommand(mqttSource, sink)
      whenReady(ready) { _ =>
        publish(topic, testMsg)

        probe.request(1)
        val msg = transformerActorProbe.expectMessageType[PrepareMessage]
        msg.replyTo ! Prepared(msg.message)

        val edaCommand = edaActorProbe.expectMessageType[PassEdaCommand]
        edaCommand.replyTo ! SendResponseError("myeeg", "netz linz", "Local address contains control or whitespace", "Send Mail")

        println("finish")

        val expectedPayload = EbMsMessage(
          conversationId = "0", sender = "myeeg", receiver = "netz linz",
          messageCode = EbMsMessageType.ERROR_MESSAGE,
          errorMessage = Some("Local address contains control or whitespace"),
          reason=Some("Send Mail")).asJson.toString

        val testResponse = probe.requestNext()
        testResponse.topic shouldBe "eda/response/myeeg/protocol/error"
        testResponse.payload.utf8String shouldBe expectedPayload
      }
    }

  }
}
