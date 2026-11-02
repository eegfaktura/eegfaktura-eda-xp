package at.energydash.mqtt

import at.energydash.domain.EbMsMessage
import at.energydash.mqtt.MqttProtocol.{EdaEventReceived, EdaInboundMessage, EdaMessageCommand, Handover, MqttMessageCmd, Terminate, TerminatedOk}
import at.energydash.testsupport.LogCapture
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.{BoundedSourceQueue, QueueOfferResult}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

// 261005-ca1: the connected MqttSystem reports whether the publishing stream took a message
class MqttSystemQueueSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  /** A publishing stream that answers every offer with `result`. */
  private def answering(result: QueueOfferResult): BoundedSourceQueue[MqttMessageCmd] = new BoundedSourceQueue[MqttMessageCmd] {
    override def offer(elem: MqttMessageCmd): QueueOfferResult = result
    override def complete(): Unit = ()
    override def fail(ex: Throwable): Unit = ()
    override def size(): Int = 0
    override def isCompleted: Boolean = false
  }

  private val msg = EbMsMessage(conversationId = "RC100181202407101720603980000000099", sender = "AT003000", receiver = "RC100181")

  "a connected MqttSystem" should {
    "accept messages while the buffer has room and refuse new ones when it is full" in {
      // a client that takes one message and never finishes it: the buffer (2) fills up after that
      val taken = Promise[Unit]()
      val stalled = Promise[Unit]()
      val queue = Source.queue[MqttMessageCmd](2).toMat(Sink.foreachAsync(1) { _ =>
        taken.trySuccess(())
        stalled.future
      })(Keep.left).run()
      val mqttSystem = testKit.spawn(MqttSystem.connected(queue))
      def send(): Handover = {
        val h = new Handover
        mqttSystem ! EdaEventReceived(EdaInboundMessage("cr_msg", msg), None, Some(h))
        h
      }
      Await.result(send().future, 3.seconds)
      Await.result(taken.future, 3.seconds) // from here nothing leaves the buffer
      val results = (1 to 4).map(_ => send()).map(h => Await.result(h.future.map(_ => "accepted").recover { case e => e.getMessage }, 3.seconds))
      results shouldBe Seq("accepted", "accepted", "MQTT publish buffer full", "MQTT publish buffer full")
      stalled.success(())
    }

    "tell the sender when the stream failed or is closed" in {
      def refusal(result: QueueOfferResult): String = {
        val h = new Handover
        testKit.spawn(MqttSystem.connected(answering(result))) ! EdaEventReceived(EdaInboundMessage("cr_msg", msg), None, Some(h))
        (the[IllegalStateException] thrownBy Await.result(h.future, 3.seconds)).getMessage
      }
      refusal(QueueOfferResult.Failure(new RuntimeException("boom"))) shouldBe "MQTT publish stream failed: boom"
      refusal(QueueOfferResult.QueueClosed) shouldBe "MQTT publish stream closed"
    }

    "log what it cannot publish when no sender waits for the result" in {
      val (_, events) = LogCapture(org.slf4j.Logger.ROOT_LOGGER_NAME) {
        val mqttSystem = testKit.spawn(MqttSystem.connected(answering(QueueOfferResult.Dropped)))
        mqttSystem ! EdaEventReceived(EdaInboundMessage("cr_msg", msg), None)
        mqttSystem ! EdaMessageCommand(CommandMessage("RC100130", "pontononlinestatus", io.circe.Json.Null), None)
        val h = new Handover // handled after the two above: the lines are written by then
        mqttSystem ! EdaEventReceived(EdaInboundMessage("cr_msg", msg), None, Some(h))
        Await.ready(h.future, 3.seconds)
      }
      val lines = LogCapture.messages(events)
      lines.count(_.contains("not published: MQTT publish buffer full")) shouldBe 2
      lines.exists(_.contains(s"conversation ${msg.conversationId}")) shouldBe true
      lines.exists(_.contains("command pontononlinestatus")) shouldBe true
    }

    "stop after Terminate although the stream has already ended" in {
      val ended = new BoundedSourceQueue[MqttMessageCmd] {
        override def offer(elem: MqttMessageCmd): QueueOfferResult = QueueOfferResult.QueueClosed
        override def complete(): Unit = throw new IllegalStateException("The queue has already been completed.")
        override def fail(ex: Throwable): Unit = ()
        override def size(): Int = 0
        override def isCompleted: Boolean = true
      }
      val mqttSystem = testKit.spawn(MqttSystem.connected(ended))
      mqttSystem ! Terminate
      mqttSystem ! TerminatedOk
      createTestProbe().expectTerminated(mqttSystem, 3.seconds)
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
