package at.energydash.domain.eda
//import model.enums.EbMsProcessType._
import org.apache.pekko.util.ByteString
import at.energydash.domain.EbMsMessage
import at.energydash.testsupport.Xsd
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

      val node = message match {
        case Right(m) => MessageHelper.getEdaMessageByType(m).get.toXML
      }

      (node \ "ProcessDirectory" \ "ConsentEnd" ).text should fullyMatch regex """[12][0-9]{3}-[01][0-9]-[0-3][0-9]"""
      (node \ "ProcessDirectory" \ "ConsentEnd" ).text shouldBe  "2023-03-31"

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

      val doc = requestObj.toByte
    }
  }

  // The optional reason of a revoke (from the web, via the backend's MQTT field "reason") reaches the grid operator:
  // CMRevoke_01p10 sends it as Reason with ReasonKey 0 ("Grund der Beendigung für ReasonKey 0"), CMRevoke_01p00 has
  // only Reason. Without a reason neither element is sent. Every document is valid against its XSD.
  "Revoke Message CCMS reason" should {
    def request(label: String, reason: Option[String]) = {
      // circe writes the reason as a JSON string (control characters escaped)
      val reasonField = reason.fold("")(r => "\"reason\":" + io.circe.Json.fromString(r).noSpaces + ",")
      val json =
        s"""{
           | "conversationId":"CC100063202406201615421120000059025",
           | "messageId":"CC100063202406201615421120000059024",
           | "sender":"CC100063",
           | "receiver":"AT003000",
           | "messageCode":"AUFHEBUNG_CCMS",
           | "messageCodeVersion":"$label",
           | "meter":{"meteringPoint":"AT0030000000000000000000000749984", "consentId":"1"},
           | $reasonField
           | "consentEnd":1718920800000}""".stripMargin
      CMRevokeRequest(decode[EbMsMessage](json).toOption.get).getVersion().get
    }
    def revoke(label: String, reason: Option[String]) = request(label, reason).toXML

    "send ReasonKey 0 with the reason in CMRevoke 01p10 (label 01.30)" in {
      val node = revoke("01.30", Some("Auszug des Mitglieds"))
      Xsd.validate(node, "CMRevoke_01p10.xsd")
      (node \ "ProcessDirectory" \ "ReasonKey").text shouldBe "0"
      (node \ "ProcessDirectory" \ "Reason").text shouldBe "Auszug des Mitglieds"
    }

    "send neither ReasonKey nor Reason without a reason in CMRevoke 01p10 (label 01.30)" in {
      for (reason <- Seq(None, Some(""), Some("   "), Some("\u00a0"), Some("\u0001\n"))) {
        val node = revoke("01.30", reason)
        Xsd.validate(node, "CMRevoke_01p10.xsd")
        (node \ "ProcessDirectory" \ "ReasonKey") shouldBe empty
        (node \ "ProcessDirectory" \ "Reason") shouldBe empty
      }
    }

    "send only Reason in CMRevoke 01p00 (labels 01.02, 01.10)" in {
      for (label <- Seq("01.02", "01.10")) {
        val node = revoke(label, Some("Auszug des Mitglieds"))
        Xsd.validate(node, "CMRevoke_01p00.xsd")
        (node \ "ProcessDirectory" \ "Reason").text shouldBe "Auszug des Mitglieds"

        val none = revoke(label, Some(" "))
        Xsd.validate(none, "CMRevoke_01p00.xsd")
        (none \ "ProcessDirectory" \ "Reason") shouldBe empty
      }
    }

    "clean control characters and every kind of white space, in both schemas" in {
      val raw = Some("\u00a0Auszug\u0001des\r\nMitglieds\t ")
      val p10 = revoke("01.30", raw)
      Xsd.validate(p10, "CMRevoke_01p10.xsd")
      (p10 \ "ProcessDirectory" \ "Reason").text shouldBe "Auszug des Mitglieds"
      (p10 \ "ProcessDirectory" \ "ReasonKey").text shouldBe "0"
      val p00 = revoke("01.02", raw)
      Xsd.validate(p00, "CMRevoke_01p00.xsd")
      (p00 \ "ProcessDirectory" \ "Reason").text shouldBe "Auszug des Mitglieds"
    }

    "drop format characters and characters XML 1.0 does not allow, as the backend does" in {
      val node = revoke("01.30", Some("\u200bAus\ufeffzug\u202e des\ufffe Mitglieds\uffff"))
      Xsd.validate(node, "CMRevoke_01p10.xsd")
      (node \ "ProcessDirectory" \ "Reason").text shouldBe "Auszug des Mitglieds"
      val none = revoke("01.30", Some("\u200b\ufeff\uffff"))
      Xsd.validate(none, "CMRevoke_01p10.xsd")
      (none \ "ProcessDirectory" \ "ReasonKey") shouldBe empty
      (none \ "ProcessDirectory" \ "Reason") shouldBe empty
      at.energydash.domain.xml.RevokeReason.clean("a\ud800b") shouldBe "ab" // a lone surrogate
    }

    "keep 50 characters and drop a longer reason instead of sending a document outside the schema" in {
      val fifty = "ä" * 50
      (revoke("01.30", Some(fifty)) \ "ProcessDirectory" \ "Reason").text shouldBe fifty
      for ((label, xsd) <- Seq("01.30" -> "CMRevoke_01p10.xsd", "01.02" -> "CMRevoke_01p00.xsd")) {
        val node = revoke(label, Some(fifty + "x"))
        Xsd.validate(node, xsd)
        (node \ "ProcessDirectory" \ "ReasonKey") shouldBe empty
        (node \ "ProcessDirectory" \ "Reason") shouldBe empty
      }
    }

    "serialise the reason as UTF-8 bytes that are valid against CMRevoke 01p10" in {
      val bytes = request("01.30", Some("Auszug – Mitglied äöü")).toByte.get.utf8String
      bytes should include("ReasonKey>0</")
      bytes should include("Reason>Auszug – Mitglied äöü</")
      Xsd.validate(scala.xml.XML.loadString(bytes), "CMRevoke_01p10.xsd")
    }
  }
}
