package at.energydash.config

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The configuration helpers (`config` package object) and the document mode of the test configuration. */
class ConfigSpec extends AnyWordSpec with Matchers {
  private val c = ConfigFactory.parseString(
    """a = "x"
      |n { create = true, v = "inner" }
      |off { create = false, v = "inner" }""".stripMargin)

  "ConfigExtensions" should {
    "read present and absent keys" in {
      c.get("a", _.toUpperCase) shouldBe "X"
      c.getConfigured("n", _.getString("v")) shouldBe "inner"
      c.getOption("a", _.getString _) shouldBe Some("x")
      c.getOption("missing", _.getString _) shouldBe None
      c.getOptionConfigured("n", _.getString("v")) shouldBe Some("inner")
      c.optionalString("a") shouldBe Some("x")
      c.optionalString("missing") shouldBe None
    }
    "create a configured value only when its flag is set" in {
      c.getOptionConfiguredIf("n", _.getString("v")) shouldBe Some("inner")
      c.getOptionConfiguredIf("off", _.getString("v")) shouldBe None
    }
    "give an empty config for an absent key" in {
      c.getConfigOrEmpty("n").getString("v") shouldBe "inner"
      c.getConfigOrEmpty("missing").isEmpty shouldBe true
    }
  }

  "Config.interfaceMode" should {
    "be SIMU in the test configuration (an invalid value needs its own config, M4)" in {
      Config.interfaceMode shouldBe "SIMU"
    }
  }
}
