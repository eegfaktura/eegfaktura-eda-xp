package at.energydash.actors.http

import com.typesafe.config.ConfigFactory
import org.apache.pekko.http.scaladsl.model.Uri
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._
import scala.xml.XML

class InboundForwardingSpec extends AnyWordSpec with Matchers {

  private val pilot = Uri("http://xp.pilot:6090/pontonxp/message")

  "receiverOf" should {
    "read the ReceiverId of the Ponton header" in {
      val envelope = XML.load(getClass.getResourceAsStream("/Response-Envelope.xml"))
      InboundForwarding.receiverOf(envelope) shouldBe Some("RC100181")
    }
    "not take the receiver from the message body" in {
      val noHeader = <Envelope><Body><InboundDocument><Message><ReceiverId>RC1</ReceiverId></Message></InboundDocument></Body></Envelope>
      InboundForwarding.receiverOf(noHeader) shouldBe None
    }
  }

  "targets" should {
    "be empty without app.forward" in {
      InboundForwarding.targets(ConfigFactory.parseString("app {}")) shouldBe empty
    }
    "be empty with the default from reference.conf" in {
      InboundForwarding.targets(ConfigFactory.defaultReference()) shouldBe empty
    }
    "read RC number -> URL, keys upper case" in {
      val cfg = ConfigFactory.parseString("""app.forward { cc100153 = "http://xp.pilot:6090/pontonxp/message" }""")
      InboundForwarding.targets(cfg) shouldBe Map("CC100153" -> pilot)
    }
    "refuse an empty or relative URL" in {
      an[IllegalArgumentException] should be thrownBy InboundForwarding.targets(ConfigFactory.parseString("""app.forward { CC100153 = "" }"""))
      an[IllegalArgumentException] should be thrownBy InboundForwarding.targets(ConfigFactory.parseString("""app.forward { CC100153 = "/pontonxp/message" }"""))
      an[IllegalArgumentException] should be thrownBy InboundForwarding.targets(ConfigFactory.parseString("""app.forward { CC100153 = "ftp://x/pontonxp/message" }"""))
      an[IllegalArgumentException] should be thrownBy InboundForwarding.targets(ConfigFactory.parseString("""app.forward { CC100153 = "http:/xp-adapter:6090/pontonxp/message" }"""))
    }
    "refuse keys that differ only in case" in {
      val cfg = ConfigFactory.parseString("""app.forward { CC100153 = "http://a:6090/m", cc100153 = "http://b:6090/m" }""")
      an[IllegalArgumentException] should be thrownBy InboundForwarding.targets(cfg)
    }
  }

  "timeouts" should {
    "default to 5 s read and 10 s answer (reference.conf, and without the keys)" in {
      InboundForwarding.timeouts(ConfigFactory.defaultReference()) shouldBe InboundForwarding.Timeouts(5.seconds, 10.seconds)
      InboundForwarding.timeouts(ConfigFactory.parseString("app {}")) shouldBe InboundForwarding.Timeouts(5.seconds, 10.seconds)
    }
    "take configured values" in {
      val cfg = ConfigFactory.parseString("app { forward-read-timeout = 12s, forward-timeout = 3500ms }")
      InboundForwarding.timeouts(cfg) shouldBe InboundForwarding.Timeouts(12.seconds, 3500.millis)
    }
    "refuse zero or negative values" in {
      an[IllegalArgumentException] should be thrownBy InboundForwarding.timeouts(ConfigFactory.parseString("app.forward-read-timeout = 0s"))
      an[IllegalArgumentException] should be thrownBy InboundForwarding.timeouts(ConfigFactory.parseString("app.forward-timeout = -1s"))
    }
  }

  "decide" should {
    val targets = Map("CC100153" -> pilot)
    "forward a listed receiver, whatever its case" in {
      InboundForwarding.decide(targets, Some("cc100153"), alreadyForwarded = false) shouldBe InboundForwarding.Forward("CC100153", pilot)
    }
    "process other receivers here" in {
      InboundForwarding.decide(targets, Some("RC100181"), alreadyForwarded = false) shouldBe InboundForwarding.Local
      InboundForwarding.decide(targets, None, alreadyForwarded = false) shouldBe InboundForwarding.Local
    }
    "refuse to forward an already forwarded message again" in {
      InboundForwarding.decide(targets, Some("CC100153"), alreadyForwarded = true) shouldBe InboundForwarding.Loop("CC100153", pilot)
    }
    "process an already forwarded message here when its receiver is not listed (the target zone)" in {
      InboundForwarding.decide(Map.empty, Some("CC100153"), alreadyForwarded = true) shouldBe InboundForwarding.Local
    }
  }
}
