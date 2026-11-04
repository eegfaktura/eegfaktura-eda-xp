package at.energydash.domain.eda
//import model.enums.EbMsProcessType._
import at.energydash.domain.EbMsMessage
import io.circe.generic.auto._
import io.circe.parser.decode
import org.scalatest.{EitherValues, OptionValues, TryValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CMRevokeMessageSpec extends AnyWordSpec with Matchers with EitherValues with OptionValues with TryValues {
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
          |  "messageCodeVersion": "01.10",
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

      val node = MessageHelper.getEdaMessageByType(message.value).value.toXML

      (node \ "ProcessDirectory" \ "ConsentEnd" ).text should fullyMatch regex """[12][0-9]{3}-[01][0-9]-[0-3][0-9]"""
      (node \ "ProcessDirectory" \ "ConsentEnd" ).text shouldBe  "2023-03-31"
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

      val requestObj = CMRevokeRequest(message.value).getVersion().success.value

      val node = requestObj.toXML
      (node \ "ProcessDirectory" \ "ConsentEnd" ).text shouldBe  "2024-06-21"
      requestObj.toByte.success.value.utf8String should include ("2024-06-21")
    }
  }
}
