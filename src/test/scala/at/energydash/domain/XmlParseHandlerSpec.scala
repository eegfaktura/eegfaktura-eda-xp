package at.energydash.domain

import at.energydash.domain.XmlParseHandler.ParseHeader
import at.energydash.domain.enums.EbMsMessageType
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scalaxb.DataRecord

import scala.io.Source
import scala.xml.XML

// platform#111 ca11: a known document eda-xp cannot map must fail (route answers 500, Ponton keeps
// it for a redelivery after the fix) instead of being acknowledged and lost
class XmlParseHandlerSpec extends AnyWordSpec with Matchers {
  import cmnotification.v01p11.CMNotification
  import ponton.`package`._

  private val header = ParseHeader("AT003000", "RC100001", Some("RC100001202307111689063030000000055"), Some("ZUSTIMMUNG_ECON"))

  private def notification(messageCode: String) = {
    val xml = Source.fromResource("ZUSTIMMUNG_ECON.xml").mkString
      .replace(">ZUSTIMMUNG_ECON<", s">$messageCode<")
    DataRecord(scalaxb.fromXML[CMNotification](XML.loadString(xml)))
  }

  "mapDataRecordToEbms" should {
    "read a known document" in {
      val m = XmlParseHandler.mapDataRecordToEbms(header, notification("ZUSTIMMUNG_ECON"))
      m.messageCode shouldBe EbMsMessageType.ONLINE_REG_APPROVAL
    }
    "fail for a MessageCode unknown to eda-xp" in {
      a[NoSuchElementException] should be thrownBy XmlParseHandler.mapDataRecordToEbms(header, notification("ZUSTIMMUNG_NEU"))
    }
  }
}
