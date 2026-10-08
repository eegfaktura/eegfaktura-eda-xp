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
}
