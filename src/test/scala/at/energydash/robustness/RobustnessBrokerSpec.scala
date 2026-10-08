package at.energydash.robustness

import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.mqtt.MqttProtocol.{EdaEventReceived, EdaInboundMessage}
import at.energydash.scenario.ScenarioSupport
import at.energydash.testsupport.TestBroker
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

/** M5 R3 on its own: it stops and replaces the test broker. */
class RobustnessBrokerSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with ScenarioSupport with Eventually {
  "R3: the broker restarts while the service runs" should {
    "deliver notifications again after the restart (MqttSystem restarts with backoff, pinned)" taggedAs Slow in {
      val g = graph("r3")
      try {
        val answer = EbMsMessage(conversationId = "C", sender = "AT003000", receiver = Tenant, messageCode = EbMsMessageType.ONLINE_REG_ANSWER)
        val publisher = g.kit.spawn(at.energydash.mqtt.MqttSystem(at.energydash.config.Config.getMqttConfig()))
        eventually(timeout(10.seconds), interval(300.millis)) {
          publisher ! EdaEventReceived(EdaInboundMessage("EC_REQ_ONL", answer), None)
          await("eda/response/rc100401/protocol/ec_req_onl", 300.millis)
        }
        broker.stopServer()
        val restarted = TestBroker.start()
        try {
          val again = new at.energydash.testsupport.MqttRecorder("eda/response/#")
          try eventually(timeout(70.seconds), interval(500.millis)) {
            publisher ! EdaEventReceived(EdaInboundMessage("EC_REQ_ONL", answer), None)
            again.next(400.millis).map(_.topic) shouldBe Some("eda/response/rc100401/protocol/ec_req_onl")
          } finally again.close()
        } finally restarted.stopServer()
      } finally g.stop()
    }
  }

}
