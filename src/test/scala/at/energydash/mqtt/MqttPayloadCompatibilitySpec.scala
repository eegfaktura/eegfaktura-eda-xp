package at.energydash.mqtt

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.GZIPInputStream

class MqttPayloadCompatibilitySpec extends AnyWordSpec with Matchers {
  "CR_MSG MQTT payload" should {
    "use the gzip plus Base64 wire format expected by energystore" in {
      val json = """{"message":"compatibility-test"}"""

      val encoded = MqttSystem.encodeProtocolPayload("CR_MSG", json)
      encoded should include ("+")
      val compressed = Base64.getDecoder.decode(encoded)
      val decoded = new GZIPInputStream(new ByteArrayInputStream(compressed)).readAllBytes()

      new String(decoded, StandardCharsets.UTF_8) shouldBe json
    }

    "leave other protocols as plain JSON" in {
      val json = """{"message":"accepted"}"""

      MqttSystem.encodeProtocolPayload("CR_REQ_PT", json) shouldBe json
    }
  }
}
