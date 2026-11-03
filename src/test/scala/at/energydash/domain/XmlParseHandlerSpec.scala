package at.energydash.domain

import at.energydash.domain.XmlParseHandler.ParseHeader
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.testsupport.LogCapture
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scalaxb.DataRecord

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
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

  "responseBodyXml" should {
    "log the code of a SOAP fault and nothing of its text" in {
      val fault = <env:Fault xmlns:env="http://www.w3.org/2003/05/soap-envelope">
        <env:Code><env:Value>env:Sender</env:Value></env:Code>
        <env:Reason><env:Text>Bad AT0030000000000000000000000526178 for max@example.com</env:Text></env:Reason>
      </env:Fault>
      val envelope = soapenvelope11.Envelope(None, soapenvelope11.Body(Seq(DataRecord(None, None, fault)), Map()), Nil, Map())
      val (result, events) = LogCapture("XmlParseHandler") {
        Await.result(XmlParseHandler.responseBodyXml(envelope)(ExecutionContext.parasitic), 5.seconds)
      }
      result shouldBe empty
      LogCapture.messages(events) shouldBe List("SOAP fault in response body, code env:Sender (fault text not logged)")
    }
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
