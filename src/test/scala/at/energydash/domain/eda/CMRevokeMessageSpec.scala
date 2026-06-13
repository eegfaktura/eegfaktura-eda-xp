package at.energydash.domain.eda
//import model.enums.EbMsProcessType._
import org.apache.pekko.util.ByteString
import at.energydash.domain.EbMsMessage
import io.circe.generic.auto._
import io.circe.parser.decode
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CMRevokeMessageSpec extends AnyWordSpec with Matchers {
  import at.energydash.domain.JsonImplicit._

  "Revoke Message" should {
    "Parse to XML" in {
      val jsonObjectStr =
        """{
          |  "messageId" : "RC100181202306071686177700000000007",
          |  "conversationId" : "TE100001202306071686177700000000008",
          |  "sender" : "TE000001",
          |  "receiver" : "AT009999",
          |  "messageCode" : "AUFHEBUNG_CCMS",
          |  "messageCodeVersion": "01.01",
          |  "requestId" : "CHKWFJ5N",
          |  "meter" : {
          |    "meteringPoint" : "AT0030000000000000000000000000101",
          |    "consentId": "1",
          |    "direction" : null
          |  },
          |  "consentEnd": 1680219900000
          |}
          |""".stripMargin

      val message = decode[EbMsMessage](jsonObjectStr)

      val node = message match {
        case Right(m) => MessageHelper.getEdaMessageByType(m).get.toXML
      }

      (node \ "ProcessDirectory" \ "ConsentEnd" ).text should fullyMatch regex """[12][0-9]{3}-[01][0-9]-[0-3][0-9]"""
      (node \ "ProcessDirectory" \ "ConsentEnd" ).text shouldBe  "2023-03-31"
      println(node)

//      val obj = CMRevokeXMLMessageV0100.fromXML(node.asInstanceOf[Elem])
//      obj match {
//        case Success(o) =>
//          o.message.messageCodeVersion shouldBe Some("01.01")
//          o.message.messageCode shouldBe EbMsMessageType.EDA_MSG_AUFHEBUNG_CCMI
//          o.message.sender shouldBe "TE000001"
//          o.message.responseData.get.head.ConsentEnd shouldBe Some(1680213600000L)
//          o.message.responseData.get.head.MeteringPoint shouldBe Some("AT0030000000000000000000000000101")
//      }
    }
  }

  "Revoke Message CCMS" should {
    "Parse to XML" in {

      val jsonObjectStr =
        """{
          | "conversationId":"CC100063202406201615421120000059025",
          | "messageId":"CC100063202406201615421120000059024",
          | "sender":"CC100063",
          | "receiver":"AT003000",
          | "messageCode":"AUFHEBUNG_CCMS",
          | "messageCodeVersion":"01.02",
          | "requestId":"KOTH2QYO",
          | "meter":{
          |   "meteringPoint":"AT0030000000000000000000000749984",
          |   "consentId": "1"
          | },
          | "ecId":"ATCC9999DYNAMCC100063000000000015",
          | "consentEnd":1718920800000}""".stripMargin
      val message = decode[EbMsMessage](jsonObjectStr)

      val requestObj = message match {
        case Right(m) =>
          CMRevokeRequest(m).getVersion().get
      }

      val node = requestObj.toXML
      (node \ "ProcessDirectory" \ "ConsentEnd" ).text shouldBe  "2024-06-21"
      println(node)

      val doc = requestObj.toByte
      println(doc.map(_.utf8String))
    }
  }
}
