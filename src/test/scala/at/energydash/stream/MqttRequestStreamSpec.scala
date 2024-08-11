package at.energydash.stream

import akka.Done
import akka.stream.alpakka.mqtt.scaladsl.MqttSource
import akka.stream.alpakka.mqtt.{MqttMessage, MqttQoS, MqttSubscriptions}
import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import at.energydash.MqttBaseSpec
import at.energydash.MqttSourceSpec.fixture
import at.energydash.actors.ConversationEntity.{InitConversation, InitDone}
import at.energydash.actors.PrepareMessageActor.{PrepareMessage, Prepared}
import at.energydash.actors.{EdaCommand, PassEdaCommand, PrepareMessageActor, SendEdaResponse}
import io.circe.generic.auto._
import io.circe.syntax.{EncoderOps, _}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Future

class MqttRequestStreamSpec extends MqttBaseSpec with AnyWordSpecLike {

  val sourceSettings = connectionSettings.withClientId(clientId = "source-spec/source")
  val sinkSettings = connectionSettings.withClientId(clientId = "source-spec/sink")

  "MQTT Stream" should {
    "Handle Mqtt message" in withBroker(Map("topic1" -> 0)) { p =>
      val f = fixture(p)
      import at.energydash.domain.JsonImplicit._
      import f._
      import f.system._

      val testMsg = """{"conversationId":"RC100699202407221900383040000107598","messageId":"RC100699202407221900383040000107597","sender":"RC100699","receiver":"AT003100","messageCode":"ANFORDERUNG_ECON","messageCodeVersion":"02.00","requestId":"48NaALA","meter":{"meteringPoint":"AT0031000000099000000000000005832","direction":"GENERATION","partFact":100},"ecId":"AT00310000000RC100699EGR000600001"}"""
      val topic = "source-spec/manualacks"
      val input = Vector("one", "two", "three", "four", "five")
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
//      val sink = Sink.head[MqttMessage]
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

        probe.requestNext().topic shouldBe "eda/response/rc100699/protocol/ec_req_onl"
      }
    }
  }
}
