package at.energydash.domain.xml

import at.energydash.testsupport.{Fixtures, LogCapture}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import ponton.`package`._

/** Inbound ECMPList 01p20: ECZoneLevel, ECType and TechCode/FuelCode reach the message (platform#229). */
class ECMPListV0120DocumentSpec extends AnyWordSpec with Matchers {
  private val doc = scalaxb.fromXML[ecmplist.v01p20.ECMPList](
    Fixtures.xml("protocol/fixtures/ECMPList-01.20-SENDEN_ECP-P2P-zone.xml"))
  private def withZone(z: Option[String]) = doc.copy(ProcessDirectory = doc.ProcessDirectory.copy(ECZoneLevel = z))

  "toMessage" should {
    "map ECZoneLevel, ECType as received and TechCode/FuelCode per metering point" in {
      val m = ECMPListV0120Document(doc).toMessage
      (m.ecZoneLevel, m.ecTypeEda, m.ecType) shouldBe ((Some(3), Some("PP"), None))
      m.meterList.get.map(x => (x.techCode, x.fuelCode)) shouldBe Seq((Some("TC01"), Some("FC01")), (None, None))
    }
    "drop an ECZoneLevel outside 1–7 with a warning, and leave it empty when absent" in {
      Seq("0", "8", "x").foreach { z =>
        val (m, logs) = LogCapture(classOf[ECMPListV0120Document].getName)(ECMPListV0120Document(withZone(Some(z))).toMessage)
        withClue(z)(m.ecZoneLevel shouldBe None)
        withClue(z)(LogCapture.messages(logs).exists(_.startsWith("ECZoneLevel not 1-7, ignored")) shouldBe true)
      }
      ECMPListV0120Document(withZone(None)).toMessage.ecZoneLevel shouldBe None
    }
  }
}
