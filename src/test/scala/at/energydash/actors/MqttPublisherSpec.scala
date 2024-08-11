package at.energydash.actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import at.energydash.EmbeddedDb
import at.energydash.actors.MqttPublisher.{EdaNotification, MqttPublish}
import at.energydash.domain.EbMsMessage
import at.energydash.mqtt.MqttProtocol.{EdaEventReceived, MqttCmd}
import io.circe.generic.auto._
import io.circe.parser.decode
import org.scalatest.wordspec.AnyWordSpecLike

class MqttPublisherSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with EmbeddedDb {
  import at.energydash.domain.JsonImplicit._
  "MqttPublisher" should {
    "receive eda ANTWORT_ECON command" in {
      val mqttSystem = createTestProbe[MqttCmd]()
      val conversationEntity = testKit.spawn(ConversationEntity())
      val ebMsAggregator = testKit.spawn(EbMsAggregator(conversationEntity))
      val mqttPublisher = testKit.spawn(MqttPublisher(mqttSystem.ref, ebMsAggregator))

      val antwortMsg =
        """{
          | "conversationId":"RC100699202407221900383040000107598",
          | "messageId":"AT003000202407221912547180293747548",
          | "sender":"AT003000",
          | "receiver":"RC102587",
          | "messageCode":"ANTWORT_ECON",
          | "messageCodeVersion":"01.11",
          | "requestId":"TvYniho",
          | "responseData":[
          |   {"MeteringPoint":"AT0030000000000000000000000258963","ResponseCode":[99]}
          | ]
          |}""".stripMargin

      val antwortJsonObj = decode[EbMsMessage](antwortMsg).toOption
      antwortJsonObj shouldBe defined

      mqttPublisher ! MqttPublish(EdaNotification("cr_req_econ", antwortJsonObj.get) :: Nil)
      val res = mqttSystem.expectMessageType[EdaEventReceived]
      res.ev.message.ecId shouldBe Some("AT00310000000RC100699EGR000600001")
      res.ev.message.responseData.get.head.MeteringPoint shouldBe Some("AT0030000000000000000000000258963")
    }

    "receive eda ANTWORT_PT command" in {
      val mqttSystem = createTestProbe[MqttCmd]()
      val conversationEntity = testKit.spawn(ConversationEntity())
      val ebMsAggregator = testKit.spawn(EbMsAggregator(conversationEntity))
      val mqttPublisher = testKit.spawn(MqttPublisher(mqttSystem.ref, ebMsAggregator))

      val antwortMsg =
        """{
          | "conversationId":"RC102537202407222114235490000107920",
          | "messageId":"AT003000202407222032159810293748157",
          | "sender":"AT003000",
          | "receiver":"RC102537",
          | "messageCode":"ANTWORT_PT",
          | "messageCodeVersion":"01.13",
          | "responseData":[{"ResponseCode":[70]}]}""".stripMargin

      val antwortJsonObj = decode[EbMsMessage](antwortMsg).toOption
      antwortJsonObj shouldBe defined

      mqttPublisher ! MqttPublish(EdaNotification("cr_req_pt", antwortJsonObj.get) :: Nil)
      val res = mqttSystem.expectMessageType[EdaEventReceived]
      res.ev.message.ecId shouldBe Some("AT00300000000RC102537000000971834")

      res.ev.message.meter shouldBe defined
      res.ev.message.meter.get.meteringPoint shouldBe "AT0030000000000000000000030083164"
    }
  }

}
