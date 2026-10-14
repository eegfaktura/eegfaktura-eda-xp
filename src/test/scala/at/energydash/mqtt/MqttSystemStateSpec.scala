package at.energydash.mqtt

import at.energydash.config.Config
import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.mqtt.MqttProtocol._
import at.energydash.testsupport.{MqttRecorder, TestBroker}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.core.read.ListAppender
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * The states of the MQTT client actor (`MqttSystem`: connecting, connected, terminating), driven by sending
 * its public protocol messages directly — deterministic, unlike R3's broker restart, which reaches these
 * paths only when the restart hits the right state (261008-ca19). R3 stays the end-to-end check.
 *
 * Observed: nothing in production sends `Terminate` or `TerminatedOk` to `MqttSystem`; after `Terminate`
 * the actor waits for a `TerminatedOk` nobody sends (pinned below).
 */
class MqttSystemStateSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Eventually {
  private val answer = EbMsMessage(conversationId = "C", sender = "AT003000", receiver = "RC100401",
    messageCode = EbMsMessageType.ONLINE_REG_ANSWER)
  private val event = EdaEventReceived(EdaInboundMessage("EC_REQ_ONL", answer), None)
  private val Topic = "eda/response/rc100401/protocol/ec_req_onl"

  private def starts[T](body: (() => Int) => T): T = {
    val logger = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext].getLogger(MqttSystem.getClass.getName).asInstanceOf[Logger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    try body(() => appender.list.asScala.toList.count(_.getFormattedMessage.startsWith("starting MQTT client")))
    finally logger.detachAppender(appender)
  }

  private def stopped(ref: ActorRef[_]): Unit = createTestProbe().expectTerminated(ref, 5.seconds)

  /** Publishes `event` until the recorder sees it: the client is connected (or connected again). */
  private def delivers(ref: ActorRef[MqttCmd], recorder: MqttRecorder, within: FiniteDuration): Unit =
    eventually(timeout(within), interval(300.millis)) {
      ref ! event
      recorder.next(250.millis).map(_.topic) shouldBe Some(Topic)
    }

  "MqttSystem while connecting (no broker)" should {
    "restart on TerminatedWithError" in starts { count =>
      val ref = spawn(MqttSystem(Config.getMqttConfig()))
      eventually(timeout(3.seconds))(count() shouldBe 1)
      ref ! TerminatedWithError(new RuntimeException("test: stream failed while connecting"))
      eventually(timeout(10.seconds), interval(200.millis))(count() should be >= 2)
      testKit.stop(ref)
    }
    "stop after Terminate and TerminatedOk" in {
      val ref = spawn(MqttSystem(Config.getMqttConfig()))
      ref ! Terminate
      ref ! TerminatedOk
      stopped(ref)
    }
  }

  "MqttSystem while connected" should {
    "publish an event with its ack and call the ack" in TestBroker.withBroker { _ =>
      val recorder = new MqttRecorder("eda/response/#")
      try {
        val ref = spawn(MqttSystem(Config.getMqttConfig()))
        delivers(ref, recorder, 10.seconds)
        val acked = new AtomicInteger()
        ref ! EdaEventReceived(EdaInboundMessage("EC_REQ_ONL", answer), Some(() => { acked.incrementAndGet(); Future.successful(Done) }))
        recorder.next(3.seconds).map(_.topic) shouldBe Some(Topic)
        eventually(timeout(3.seconds))(acked.get shouldBe 1)
        testKit.stop(ref)
      } finally recorder.close()
    }
    "ignore TerminatedOk, restart on TerminatedWithError and deliver again" in TestBroker.withBroker { _ =>
      val recorder = new MqttRecorder("eda/response/#")
      try starts { count =>
        val ref = spawn(MqttSystem(Config.getMqttConfig()))
        delivers(ref, recorder, 10.seconds)
        ref ! TerminatedOk
        delivers(ref, recorder, 3.seconds)
        val before = count()
        ref ! TerminatedWithError(new RuntimeException("test: stream failed while connected"))
        eventually(timeout(10.seconds), interval(200.millis))(count() shouldBe before + 1)
        delivers(ref, recorder, 15.seconds)
        testKit.stop(ref)
      } finally recorder.close()
    }
    "after Terminate ignore events and wait for TerminatedOk, then stop (pinned)" in TestBroker.withBroker { _ =>
      val recorder = new MqttRecorder("eda/response/#")
      try {
        val ref = spawn(MqttSystem(Config.getMqttConfig()))
        delivers(ref, recorder, 10.seconds)
        ref ! Terminate
        ref ! event
        recorder.next(500.millis) shouldBe None
        createTestProbe().expectNoMessage(200.millis)
        ref ! TerminatedOk
        stopped(ref)
      } finally recorder.close()
    }
    "after Terminate stop on TerminatedWithError" in TestBroker.withBroker { _ =>
      val recorder = new MqttRecorder("eda/response/#")
      try {
        val ref = spawn(MqttSystem(Config.getMqttConfig()))
        delivers(ref, recorder, 10.seconds)
        ref ! Terminate
        ref ! TerminatedWithError(new RuntimeException("test: stream failed while terminating"))
        stopped(ref)
      } finally recorder.close()
    }
  }
}
