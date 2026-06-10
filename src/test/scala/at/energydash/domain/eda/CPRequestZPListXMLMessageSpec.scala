package at.energydash.domain.eda

import at.energydash.domain.enums.{EbMsMessageType, MeterDirectionType}
import at.energydash.domain.{EbMsMessage, Meter}
import io.circe.generic.auto._
import io.circe.parser._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import ponton.OutHeaderType
import ponton.`package`.__NodeXMLFormat
import scalaxb.DataRecord
import soapenvelope11.{Body, Envelope, Header}

import scala.xml.NamespaceBinding

class CPRequestZPListXMLMessageSpec extends AnyWordSpecLike with Matchers {
    import at.energydash.domain.JsonImplicit._

    "Request POD_LIST Message" should {
      "build XML File" in {

        val scope: NamespaceBinding = scalaxb.toScope(
          //    Some("cp") -> "http://www.ebutilities.at/schemata/customerprocesses/ecmplist/01p10",
          //    Some("cp1") -> "http://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12",
          //    Some("ct") -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
          Some("oe1") -> "http://www.ebutilities.at/datenplattform/0700",
          //    Some("oe") -> "http://xp.ponton.de/eda/v320",
          Some("tns") -> "http://xp.ponton.de/eda/v320",
          Some("xs") -> "http://www.w3.org/2001/XMLSchema",
          Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance")

        val testMessage = EbMsMessage(
          conversationId = "AT003000202303041506076450000003761",
          requestId = Some("5JWLV5Z3"),
          messageId = Some("RC100181202303041506080740000003762"),
          sender = "RC100130", receiver = "AT003000", messageCode = EbMsMessageType.ONLINE_REG_INIT, messageCodeVersion = Some("02.00"),
          meter = Some(Meter("AT0030000000000000000000000655856", Some(MeterDirectionType.CONSUMPTION))), ecId = Some("AT00300000000RC100181000000956509"))

        val header = OutHeaderType(
          MessageId = "10000",
          SenderId = "edaMessage.sender",
          ReceiverId = "edaMessage.receiver",
          MessageVersion = "07.00",
          MessageType = "CustomerMeteringPointRequest",
          LogInfo = Some("VFEEG-OUT"))

        val xmlObj = CPRequestZPList(testMessage).getVersion().get
        val record = xmlObj.toRecord
        println(record)

        val node = CPRequestZPList(testMessage).getVersion().map(_.toXML).get
        println(node)

        val body = ponton.Message2(message2option = record)
//        scalaxb.toXML(ponton.OutboundDocument(header, body), targetNamespace, "OutboundDocument",
//          scalaxb.toScope(scalaxb.fromScope(scope).foldRight(scalaxb.fromScope(xmlObj.toScope)){ (a, b) => a :: b }.reverse : _*/*.distinct: _**/)
//        )
        val bodyDoc = scalaxb.toXML(ponton.OutboundDocument(header, body), None, Some("OutboundDocument"),
          scalaxb.toScope(scalaxb.fromScope(scope).foldRight(scalaxb.fromScope(xmlObj.toScope)){ (a, b) => a :: b }.reverse : _*/*.distinct: _**/), true
        )
        println(bodyDoc)

        val headers = Nil
        val bodyRecords = bodyDoc.toSeq map { DataRecord(None, None, _) }
        val headerOption = headers.toSeq.headOption map { _ =>
          Header(headers.toSeq map {DataRecord(None, None, _)}, Map())
        }
        val envelope = Envelope(headerOption, Body(bodyRecords, Map()), Nil, Map())

//        (node \ "MarketParticipantDirectory" \ "MessageCode").text shouldBe EbMsMessageType.ONLINE_REG_INIT.toString
//        (node \ "ProcessDirectory" \ "MeteringPoint").text shouldBe "AT0030000000000000000000000655856"
//        (node \ "ProcessDirectory" \ "CMRequest" \ "ECID").text shouldBe "AT00300000000RC100181000000956509"
//        (node \ "ProcessDirectory" \ "CMRequest" \ "EnergyDirection").text shouldBe MeterDirectionType.CONSUMPTION.toString
      }
    }

    "Energy XML File" should {
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
