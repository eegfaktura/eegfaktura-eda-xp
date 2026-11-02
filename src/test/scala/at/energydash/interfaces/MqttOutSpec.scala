package at.energydash.interfaces

import at.energydash.config.Config
import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.mqtt.MqttProtocol.{EdaEventReceived, EdaInboundMessage, EdaMessageCommand, MqttCmd}
import at.energydash.mqtt.{CommandMessage, MqttSystem}
import at.energydash.testsupport.{MqttRecorder, TestBroker}
import io.circe.parser.parse
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import scala.concurrent.duration._

/** Outbound MQTT (`MqttSystem`) on the embedded broker: topics, payload encodings, QoS. */
class MqttOutSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  private val broker = TestBroker.start()
  private val recorder = new MqttRecorder("eda/response/#")
  private val mqtt: ActorRef[MqttCmd] = spawn(MqttSystem(Config.getMqttConfig()))

  override def afterAll(): Unit = { recorder.close(); super.afterAll(); broker.stopServer() }

  /** MqttSystem drops events until it is connected — send until the first one arrives. */
  private def sendUntilReceived(cmd: MqttCmd): recorder.Received =
    Iterator.continually { mqtt ! cmd; recorder.next(300.millis) }.take(30).collectFirst { case Some(r) => r }
      .getOrElse(fail("nothing published within 9 s"))

  private val answer = EbMsMessage(messageId = Some("M1"), conversationId = "C1", sender = "AT003000", receiver = "RC100401",
    messageCode = EbMsMessageType.ONLINE_REG_ANSWER, ecId = Some("EC1"))

  "MqttSystem" should {
    "publish a protocol event as JSON without nulls on eda/response/<receiver>/protocol/<process>" in {
      val r = sendUntilReceived(EdaEventReceived(EdaInboundMessage("EC_REQ_ONL", answer), None))
      r.topic shouldBe "eda/response/rc100401/protocol/ec_req_onl"
      (r.qos, r.retained) shouldBe ((1, false))
      val json = parse(r.payload).toOption.get
      json.hcursor.downField("messageCode").as[String] shouldBe Right("ANTWORT_ECON")
      r.payload should not include "null"
    }
    "publish CR_MSG as Base64(gzip(JSON)) on …/protocol/cr_msg (energystore's format)" in {
      val crmsg = answer.copy(messageCode = EbMsMessageType.ENERGY_FILE_RESPONSE)
      val r = sendUntilReceived(EdaEventReceived(EdaInboundMessage("CR_MSG", crmsg), None))
      r.topic shouldBe "eda/response/rc100401/protocol/cr_msg"
      val json = new String(new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder.decode(r.payload))).readAllBytes(), "UTF-8")
      parse(json).toOption.get.hcursor.downField("messageCode").as[String] shouldBe Right("DATEN_CRMSG")
    }
    "publish a command on eda/response/<tenant>/command/<command>" in {
      val r = sendUntilReceived(EdaMessageCommand(CommandMessage("RC100401", "pontonOnlineState", parse("""{"online":true}""").toOption.get), None))
      r.topic shouldBe "eda/response/rc100401/command/pontononlinestate"
      r.payload shouldBe """{"online":true}"""
    }
  }
}
