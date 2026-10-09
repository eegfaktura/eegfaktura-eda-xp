package at.energydash.domain.eda

import at.energydash.domain.eda.MessageHelper.{buildCalendarDate, getNow, getProcessDate}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate

class MessageHelperSpec extends AnyWordSpec with Matchers {

  "ProcessDate" should {
    "parse in Timezone" in {
      println(getProcessDate)
      println(getNow(Some(1)))
      println(LocalDate.now())

      var midnight = new java.util.Date(java.util.Date.UTC(2026-1900,0,1,22,59,59))
      println(midnight)
      println(buildCalendarDate(midnight))
    }
  }


  "buildMessageId" should {
    "use the calendar year, not the week-based year" in {
      val cal = new java.util.GregorianCalendar(2026, java.util.Calendar.DECEMBER, 29, 12, 0)
      MessageHelper.buildMessageId("RC100130", 1, cal.getTime) should startWith("RC10013020261229")
    }
  }

  "toXmlDateTime" should {
    "carry the daylight saving offset" in {
      val vienna = java.util.TimeZone.getTimeZone("Europe/Vienna")
      val summer = new java.util.GregorianCalendar(vienna)
      summer.clear(); summer.set(2026, java.util.Calendar.JULY, 1, 12, 0, 0)
      MessageHelper.toXmlDateTime(summer).toXMLFormat should endWith("+02:00")
      val winter = new java.util.GregorianCalendar(vienna)
      winter.clear(); winter.set(2026, java.util.Calendar.JANUARY, 15, 12, 0, 0)
      MessageHelper.toXmlDateTime(winter).toXMLFormat should endWith("+01:00")
    }
  }

  // platform#111: the sender gets the reason instead of "No XML mapping"; GN needs a label too
  "getEdaMessageByTypeTry" should {
    import at.energydash.domain.EbMsMessage
    import at.energydash.domain.enums.EbMsMessageType

    def request(code: EbMsMessageType.Value, label: Option[String]) = EbMsMessage(
      conversationId = "RC100001202610090000000000000000001", messageId = Some("RC100001202610090000000000000000002"),
      sender = "RC100001", receiver = "AT003000", messageCode = code, messageCodeVersion = label,
      ecId = Some("AT00300000000RC100001000000000001"))

    "name the unknown version label in the failure" in {
      val result = MessageHelper.getEdaMessageByTypeTry(request(EbMsMessageType.ONLINE_REG_INIT, Some("09.99")))
      result.isFailure shouldBe true
      result.failed.get.getMessage should include("09.99")
      MessageHelper.getEdaMessageByType(request(EbMsMessageType.ONLINE_REG_INIT, Some("09.99"))) shouldBe None
    }
    "fail for a message type without a request document" in {
      MessageHelper.getEdaMessageByTypeTry(request(EbMsMessageType.ZP_LIST_RESPONSE, None)).failed.get.getMessage should
        include("No XML mapping for message type SENDEN_ECP")
    }
    "build ANFORDERUNG_GN only with the label of its body (03.12)" in {
      MessageHelper.getEdaMessageByTypeTry(request(EbMsMessageType.EEG_BASE_DATA, None)).isFailure shouldBe true
      MessageHelper.getEdaMessageByTypeTry(request(EbMsMessageType.EEG_BASE_DATA, Some("03.40"))).isFailure shouldBe true
      MessageHelper.getEdaMessageByTypeTry(request(EbMsMessageType.EEG_BASE_DATA, Some("03.12"))).isSuccess shouldBe true
    }
    "keep the GN wire strings after the enum rename" in {
      EbMsMessageType.EEG_BASE_REJECTION.toString shouldBe "ABLEHNUNG_GN"
      EbMsMessageType.EEG_BASE_RESPONSE.toString shouldBe "ANTWORT_GN"
    }
  }
}
