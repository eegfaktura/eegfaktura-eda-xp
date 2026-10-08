package at.energydash.domain

import at.energydash.domain.dao.TenantConfig
import at.energydash.domain.enums.{EbMsMessageType, MeterDirectionType}
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.Date

/** The MQTT wire format of `EbMsMessage` and the wire strings of the message codes. */
class MessageCodecSpec extends AnyWordSpec with Matchers {
  import at.energydash.domain.JsonImplicit._

  "EbMsMessage JSON" should {
    "carry dates as epoch milliseconds and round-trip" in {
      val m = EbMsMessage(conversationId = "C", sender = "RC100401", receiver = "AT003000",
        messageCode = EbMsMessageType.ENERGY_SYNC_REQ, timeline = Some(Timeline(new Date(4070905200000L), new Date(4102354800000L))),
        meter = Some(Meter("AT0030000000000000000000000401001", Some(MeterDirectionType.GENERATION), partFact = Some(BigDecimal(50)))))
      val json = m.asJson.deepDropNullValues
      json.hcursor.downField("timeline").downField("from").as[Long] shouldBe Right(4070905200000L)
      json.hcursor.downField("messageCode").as[String] shouldBe Right("ANFORDERUNG_PT")
      json.hcursor.downField("meter").downField("direction").as[String] shouldBe Right("GENERATION")
      json.noSpaces should not include "null"
      decode[EbMsMessage](json.noSpaces) shouldBe Right(m)
    }
    "reject an unknown message code" in {
      decode[EbMsMessage]("""{"conversationId":"C","sender":"S","receiver":"R","messageCode":"ANFORDERUNG_XYZ"}""").isLeft shouldBe true
    }
  }

  "EbMsMessageType wire strings" should {
    "keep the GN strings although the names are swapped (261005-ca15, pinned)" in {
      EbMsMessageType.EEG_BASE_RESPONSRE.toString shouldBe "ABLEHNUNG_GN"
      EbMsMessageType.EEG_BASE_REJECTION.toString shouldBe "ANTWORT_GN"
    }
    "be unique" in {
      val all = EbMsMessageType.values.toList.map(_.toString)
      all.diff(all.distinct) shouldBe empty
    }
  }

  "TenantConfig.toMap" should {
    "build the javax.mail properties from the security settings" in {
      val c = TenantConfig("t", "MAIL", Some("email.com"), Some("imap.email.com"), Some(143), Some("smtp.email.com"),
        Some(25), Some("u"), Some("p"), Some("STARTTLS"), Some("SSL"), active = true)
      val m = c.toMap
      m("mail.imap.host") shouldBe "imap.email.com"
      m("mail.smtp.port") shouldBe Integer.valueOf(25)
      m.get("mail.imap.starttls.enable") shouldBe Some(java.lang.Boolean.TRUE)
      m.get("mail.smtp.ssl.enable") shouldBe Some(java.lang.Boolean.TRUE)
      m.contains("mail.smtp.starttls.enable") shouldBe false
      c.toAuthMap shouldBe Map("username" -> "u", "password" -> "p")
    }
  }
}
