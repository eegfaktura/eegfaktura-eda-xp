package at.energydash.mqtt

import at.energydash.domain.EbMsMessage
import at.energydash.mqtt.MqttProtocol.{EdaEventReceived, EdaInboundMessage, Handover, MqttMessageCmd}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

// 261005-ca1: the connected MqttSystem reports whether the publishing stream took a message
class MqttSystemQueueSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  private val msg = EbMsMessage(conversationId = "RC100181202407101720603980000000099", sender = "AT003000", receiver = "RC100181")

  "a connected MqttSystem" should {
    "accept messages while the buffer has room and refuse new ones when it is full" in {
      // a client that takes one message and never finishes it: the buffer (2) fills up
      val stalled = Promise[Unit]()
      val queue = Source.queue[MqttMessageCmd](2).toMat(Sink.foreachAsync(1)(_ => stalled.future))(Keep.left).run()
      val mqttSystem = testKit.spawn(MqttSystem.connected(queue))
      val handovers = (1 to 6).map { _ =>
        val h = new Handover
        mqttSystem ! EdaEventReceived(EdaInboundMessage("cr_msg", msg), None, Some(h))
        h
      }
      val results = Await.result(Future.sequence(handovers.map(_.future.map(_ => "accepted").recover { case e => e.getMessage })), 5.seconds)
      results.takeWhile(_ == "accepted").size should (be >= 2 and be <= 3)
      results.dropWhile(_ == "accepted") should not be empty
      results.dropWhile(_ == "accepted").foreach(_ shouldBe "MQTT publish buffer full")
      stalled.success(())
    }

    "not take a message the sender already gave up on" in {
      val queue = Source.queue[MqttMessageCmd](2).toMat(Sink.ignore)(Keep.left).run()
      val mqttSystem = testKit.spawn(MqttSystem.connected(queue))
      val h = new Handover
      h.giveUp(new IllegalStateException("gave up")) shouldBe true
      mqttSystem ! EdaEventReceived(EdaInboundMessage("cr_msg", msg), None, Some(h))
      (the[IllegalStateException] thrownBy Await.result(h.future, 1.second)).getMessage shouldBe "gave up"
    }
  }
}
