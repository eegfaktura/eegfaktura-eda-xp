package at.energydash.domain

import at.energydash.domain.XmlParseHandler.ParseHeader
import at.energydash.domain.enums.EbMsMessageType
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scalaxb.DataRecord

import scala.io.Source
import scala.xml.XML

// platform#111 ca11: a known document that cannot be read must reach the receiver as an error
class XmlParseHandlerSpec extends AnyWordSpec with Matchers {
  import cmnotification.v01p11.CMNotification
  import ponton.`package`._

  private val header = ParseHeader("AT003000", "RC100001", Some("RC100001202307111689063030000000055"), Some("ZUSTIMMUNG_ECON"))

  private def notification(messageCode: String) = {
    val xml = Source.fromResource("ZUSTIMMUNG_ECON.xml").mkString
      .replace(">ZUSTIMMUNG_ECON<", s">$messageCode<")
    DataRecord(scalaxb.fromXML[CMNotification](XML.loadString(xml)))
  }

  "mapDataRecordToEbmsOrError" should {
    "read a known document as before" in {
      val m = XmlParseHandler.mapDataRecordToEbmsOrError(header, notification("ZUSTIMMUNG_ECON"))
      m.messageCode shouldBe EbMsMessageType.ONLINE_REG_APPROVAL
    }
    "turn a MessageCode unknown to eda-xp into an error for the receiver" in {
      val m = XmlParseHandler.mapDataRecordToEbmsOrError(header, notification("ZUSTIMMUNG_NEU"))
      m.messageCode shouldBe EbMsMessageType.ERROR_MESSAGE
      m.receiver shouldBe "RC100001"
      m.conversationId shouldBe "RC100001202307111689063030000000055"
      m.errorMessage.get should include("ZUSTIMMUNG_NEU")
    }
  }
}
