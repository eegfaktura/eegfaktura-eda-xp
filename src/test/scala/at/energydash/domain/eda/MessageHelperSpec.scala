package at.energydash.domain.eda

import at.energydash.domain.eda.MessageHelper._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{LocalDate, ZoneId, ZonedDateTime}
import java.util.Date

/** The id, date and calendar helpers every outbound document uses (the code mapping is guard 6 of the catalog). */
class MessageHelperSpec extends AnyWordSpec with Matchers {
  private val Vienna = ZoneId.of("Europe/Vienna")
  private def at(s: String): Date = Date.from(ZonedDateTime.of(java.time.LocalDateTime.parse(s), Vienna).toInstant)

  "buildRequestId" should {
    "be the Base58 of CRC32 + CRC8 of the message id (known vectors)" in {
      buildRequestId("RC100401209901010000000000000001") shouldBe "6VfyXmE"
      buildRequestId("AT003000202303041506076450000003762") shouldBe "KNBJww"
    }
    "use only the Base58 alphabet and at most 7 characters" in {
      (1 to 200).map(i => buildRequestId(s"RC100401${"%024d".format(i)}")).foreach { id =>
        id.length should (be >= 1 and be <= 7)
        all(id.toSeq) should (be >= '1' and be <= 'z')
        id.exists("0OIl".contains(_)) shouldBe false
      }
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

  "buildMessageId" should {
    "be participant + yyyyMMdd + 10 s ticks + 10-digit sequence" in {
      val before = LocalDate.now(Vienna)
      val id = buildMessageId("RC100401", 42)
      val after = LocalDate.now(Vienna)
      id should startWith ("RC100401")
      id should endWith ("0000000042")
      val date = id.substring(8, 16)
      Seq(before, after).map(_.toString.replace("-", "")) should contain (date)
    }
    "use the calendar year, not the week-based year (261005-ca9, fixed in 1.0.8)" in {
      val cal = new java.util.GregorianCalendar(2026, java.util.Calendar.DECEMBER, 29, 12, 0)
      MessageHelper.buildMessageId("RC100130", 1, cal.getTime) should startWith("RC10013020261229")
    }
    "format the sequence with ten digits" in {
      formatSeqNumber(7) shouldBe "0000000007"
      formatSeqNumber(1234567890L) shouldBe "1234567890"
    }
  }

  "buildCalendarDate" should {
    "give the Vienna date around both DST switches and at local midnight" in {
      buildCalendarDate(at("2026-03-29T00:30")) shouldBe "2026-03-29"
      buildCalendarDate(at("2026-03-29T03:30")) shouldBe "2026-03-29"
      buildCalendarDate(at("2026-10-25T02:30")) shouldBe "2026-10-25"
      buildCalendarDate(at("2026-12-31T23:59")) shouldBe "2026-12-31"
      buildCalendarDate(at("2027-01-01T00:00")) shouldBe "2027-01-01"
    }
  }

  "getProcessDate" should {
    "be tomorrow in Vienna (today if the run crossed midnight)" in {
      Seq(getNow().toString, getNow(Some(1)).toString) should contain (getProcessDate)
    }
  }

  "DocumentCreationDateTime (xmlDateTime(date))" should {
    "carry +01:00 in winter" in {
      xmlDateTime(at("2026-01-14T10:00")).toXMLFormat should endWith ("+01:00")
    }
    "carry +02:00 in summer (261008-ca12, fixed in 1.0.8)" in {
      xmlDateTime(at("2026-07-14T10:00")).toXMLFormat should endWith ("+02:00")
    }
  }
}
