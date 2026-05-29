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

}
