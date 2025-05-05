package at.energydash.domain.eda

import at.energydash.domain.eda.MessageHelper.{buildCalendarDate, getProcessDate}
import at.energydash.domain.enums.{EbMsMessageType, MeterDirectionType}
import at.energydash.domain.{EbMsMessage, Meter, XmlParseHandler}
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.io.Source

class CMRequestOnlineRegistrationSpec extends AnyWordSpecLike with Matchers {
  import at.energydash.domain.JsonImplicit._

  "Registration Online Message" should {
    "build XML File" in {

      val testMessage = EbMsMessage(
        conversationId = "AT003000202303041506076450000003761",
        requestId = Some("5JWLV5Z3"),
        messageId = Some("RC100181202303041506080740000003762"),
        sender = "RC100130", receiver = "AT003000", messageCode = EbMsMessageType.ONLINE_REG_INIT, messageCodeVersion = Some("02.00"),
        meter = Some(Meter("AT0030000000000000000000000655856", Some(MeterDirectionType.CONSUMPTION))), ecId = Some("AT00300000000RC100181000000956509"))

      val node = CMRequestRegistrationOnlineXMLMessageV0200(testMessage).toXML

      (node \ "MarketParticipantDirectory" \ "MessageCode").text shouldBe EbMsMessageType.ONLINE_REG_INIT.toString
      (node \ "ProcessDirectory" \ "MeteringPoint").text shouldBe "AT0030000000000000000000000655856"
      (node \ "ProcessDirectory" \ "CMRequest" \ "ECID").text shouldBe "AT00300000000RC100181000000956509"
      (node \ "ProcessDirectory" \ "CMRequest" \ "EnergyDirection").text shouldBe MeterDirectionType.CONSUMPTION.toString
      (node \ "ProcessDirectory" \ "ProcessDate").text shouldBe buildCalendarDate(getProcessDate.getTime)
    }

    "build 02.10 XML File" in {

      val testMessage = EbMsMessage(
        conversationId = "AT003000202303041506076450000003761",
        requestId = Some("5JWLV5Z3"),
        messageId = Some("RC100181202303041506080740000003762"),
        sender = "RC100130", receiver = "AT003000", messageCode = EbMsMessageType.ONLINE_REG_INIT, messageCodeVersion = Some("02.10"),
        meter = Some(Meter("AT0030000000000000000000000655856", Some(MeterDirectionType.CONSUMPTION))), ecId = Some("AT00300000000RC100181000000956509"))

      val node = CMRequestRegistrationOnlineXMLMessageV0210(testMessage).toXML

      (node \ "MarketParticipantDirectory" \ "MessageCode").text shouldBe EbMsMessageType.ONLINE_REG_INIT.toString
      (node \ "ProcessDirectory" \ "MeteringPoint").text shouldBe "AT0030000000000000000000000655856"
      (node \ "ProcessDirectory" \ "CMRequest" \ "ECID").text shouldBe "AT00300000000RC100181000000956509"
      (node \ "ProcessDirectory" \ "CMRequest" \ "EnergyDirection").text shouldBe MeterDirectionType.CONSUMPTION.toString
      (node \ "ProcessDirectory" \ "ProcessDate").text shouldBe buildCalendarDate(getProcessDate.getTime)
    }

    "build from JsonFile" in {
      val testMessage = """{"conversationId":"12","messageId":"34","requestId":"T672AGJ2","sender":"RC102728","receiver":"AT003000","messageCode":"ANFORDERUNG_ECON","messageCodeVersion":"02.00","meter":{"meteringPoint":"AT0030000000000000000000000179843","direction":"CONSUMPTION","partFact":100,"from":1726444800000},"ecId":"AT00300000000RC102728000000972173"}"""

      val message = decode[EbMsMessage](testMessage)

      val node = message match {
        case Right(m) => CMRequestRegistrationOnlineXMLMessageV0200(m).toXML
      }

      (node \ "MarketParticipantDirectory" \ "MessageCode").text shouldBe EbMsMessageType.ONLINE_REG_INIT.toString
      (node \ "ProcessDirectory" \ "MeteringPoint").text shouldBe "AT0030000000000000000000000179843"
      (node \ "ProcessDirectory" \ "CMRequest" \ "ECID").text shouldBe "AT00300000000RC102728000000972173"
      (node \ "ProcessDirectory" \ "CMRequest" \ "EnergyDirection").text shouldBe MeterDirectionType.CONSUMPTION.toString

      println(node)
    }
  }

  "Energy XML File" should {
    "Parse from ABSCHLUSS-ECON XML" in {
      val xmlFile = scala.xml.XML.load(Source.fromResource("message-abschluss-econ.xml").reader())
      val ebms = XmlParseHandler.mapXmlToEbms(XmlParseHandler.ParseHeader("RC100181", "AT003000"), xmlFile)

      ebms.messageCode shouldBe EbMsMessageType.ONLINE_REG_COMPLETION
    }

    "BUG: Hofmann-PartFact" in {
      val jsonObjectStr = """ {
        | "consentEnd": null,
        | "conversationId": "RC100590202404251714030620000012569",
        | "ecDisModel": null,
        | "ecId": "AT00200000000RC100590000000000256",
        | "ecType": null,
        | "energy": null,
        | "errorMessage": null,
        | "messageCode": "ANFORDERUNG_ECON",
        | "messageCodeVersion": "02.00",
        | "messageId": "RC100590202404251714030620000012568",
        | "meter": {
        |   "activation": null,
        |   "direction": "GENERATION",
        |   "from": null,
        |   "meteringPoint": "AT0020000000000000000000020901971",
        |   "partFact": 90,
        |   "plantCategory": null,
        |   "share": null,
        |   "to": null
        | },
        | "meterList": null,
        | "reason": null,
        | "receiver": "AT002000",
        | "requestId": "6NEGUJJ5",
        | "responseData": null,
        | "sender": "RC100590",
        | "timeline": null
        |}""".stripMargin

      val message = decode[EbMsMessage](jsonObjectStr)

      val node = message match {
        case Right(m) => CMRequestRegistrationOnlineXMLMessageV0200(m).toXML
      }
      (node \\ "ECPartFact").text shouldBe "90"
      (node \\ "ProcessDirectory" \ "MeteringPoint").text shouldBe "AT0020000000000000000000020901971"
      println(node)
    }
  }

}
