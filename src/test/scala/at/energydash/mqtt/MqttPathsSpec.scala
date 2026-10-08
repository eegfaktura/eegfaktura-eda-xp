package at.energydash.mqtt

import at.energydash.mqtt.path.MqttPaths
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MqttPathsSpec extends AnyWordSpec with Matchers {
  private object Paths extends MqttPaths {
    def protocol(t: String, p: String): String = edaProtocolModulePath(t, p)
    def command(t: String, c: String): String = edaCommandModulePath(t, c)
  }

  "MQTT paths" should {
    "lower-case the tenant and the process" in {
      Paths.edaReqResPath("RC100401", "EC_REQ_ONL") shouldBe "eda/response/rc100401/protocol/ec_req_onl"
    }
    "map an empty tenant to error" in {
      Paths.edaReqResPath("", "error") shouldBe "eda/response/error/protocol/error"
    }
    "normalise cr_msg_03.03 to cr_msg (the energystore subscription)" in {
      Paths.protocol("RC100401", "CR_MSG_03.03") shouldBe "rc100401/protocol/cr_msg"
      Paths.protocol("RC100401", "CR_MSG") shouldBe "rc100401/protocol/cr_msg"
    }
    "build command paths" in {
      Paths.command("RC100401", "pontonOnlineState") shouldBe "rc100401/command/pontononlinestate"
    }
  }
}
