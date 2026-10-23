package at.energydash.actors

import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import at.energydash.EmbeddedDb
import at.energydash.actors.ConversationEntity.{MergeError, MergeNotification}
import at.energydash.actors.MqttPublisher.{AggregateNotification, EdaNotification, MqttCommand, MqttPublish, MqttPublishError}
import at.energydash.domain.EbMsMessage
import at.energydash.mqtt.MqttProtocol.{EdaEventReceived, MqttCmd}
import io.circe.generic.auto._
import io.circe.parser.decode
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Promise}

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

  // 261005-ca1: MqttPublish(…, delivered) completes only after the broker confirmed the publish
  "MqttPublisher with a delivery promise" should {
    val notification = EdaNotification("cr_req_pt", EbMsMessage(conversationId = "RC100181202407101720603980000000011",
      sender = "AT003000", receiver = "RC100181", messageCode = at.energydash.domain.enums.EbMsMessageType.ZP_LIST_RESPONSE))
    /** Aggregator that hands the notifications back unchanged. */
    val echo = Behaviors.receiveMessage[MqttCommand] {
      case AggregateNotification(_, n, replyTo) => replyTo ! MqttPublish(n); Behaviors.same
      case _ => Behaviors.same
    }

    "complete when the broker acknowledges, not before" in {
      val mqttSystem = createTestProbe[MqttCmd]()
      val publisher = testKit.spawn(MqttPublisher(mqttSystem.ref, testKit.spawn(echo)))
      val delivered = Promise[Done]()
      publisher ! MqttPublish(notification :: Nil, Some(delivered))
      val ev = mqttSystem.expectMessageType[EdaEventReceived]
      ev.ev.message.conversationId shouldBe notification.message.conversationId
      ev.ack shouldBe defined
      delivered.isCompleted shouldBe false
      Await.result(ev.ack.get(), 1.second)
      Await.result(delivered.future, 1.second) shouldBe Done
    }

    "fail when MQTT reports the event as not published" in {
      val mqttSystem = createTestProbe[MqttCmd]()
      val publisher = testKit.spawn(MqttPublisher(mqttSystem.ref, testKit.spawn(echo)))
      val delivered = Promise[Done]()
      publisher ! MqttPublish(notification :: Nil, Some(delivered))
      mqttSystem.expectMessageType[EdaEventReceived].nack.get(new IllegalStateException("MQTT client not connected"))
      the[IllegalStateException] thrownBy Await.result(delivered.future, 1.second) should have message "MQTT client not connected"
    }

    "fail and publish nothing when the aggregator fails" in {
      val mqttSystem = createTestProbe[MqttCmd]()
      val failing = Behaviors.receiveMessage[MqttCommand] {
        case AggregateNotification(tenant, _, replyTo) => replyTo ! MqttPublishError(tenant, "boom"); Behaviors.same
        case _ => Behaviors.same
      }
      val publisher = testKit.spawn(MqttPublisher(mqttSystem.ref, testKit.spawn(failing)))
      val delivered = Promise[Done]()
      publisher ! MqttPublish(notification :: Nil, Some(delivered))
      the[IllegalStateException] thrownBy Await.result(delivered.future, 3.seconds) should have message "aggregator failed: boom"
      mqttSystem.expectNoMessage(300.millis)
    }

    "fail when the aggregator does not answer" in {
      val mqttSystem = createTestProbe[MqttCmd]()
      val silent = testKit.spawn(Behaviors.ignore[MqttCommand])
      val publisher = testKit.spawn(MqttPublisher(mqttSystem.ref, silent))
      val delivered = Promise[Done]()
      publisher ! MqttPublish(notification :: Nil, Some(delivered))
      (the[IllegalStateException] thrownBy Await.result(delivered.future, 8.seconds)).getMessage should startWith("aggregator did not answer")
      mqttSystem.expectNoMessage(300.millis)
    }

    "fail when a MergeError replaces the message, which still goes to the error topic as before" in {
      val mqttSystem = createTestProbe[MqttCmd]()
      val conversationEntity = testKit.spawn(Behaviors.receiveMessage[EdaCommand] {
        case MergeNotification(_, replyTo) => replyTo ! MergeError("db down"); Behaviors.same
        case _ => Behaviors.same
      })
      val publisher = testKit.spawn(MqttPublisher(mqttSystem.ref, testKit.spawn(EbMsAggregator(conversationEntity))))
      val delivered = Promise[Done]()
      publisher ! MqttPublish(notification :: Nil, Some(delivered))
      (the[IllegalStateException] thrownBy Await.result(delivered.future, 3.seconds)).getMessage should include("db down")
      val ev = mqttSystem.expectMessageType[EdaEventReceived]
      ev.ev.protocol shouldBe EbMsAggregator.ReplacedProtocol
      ev.ack shouldBe None
    }
  }

}
