package at.energydash.testsupport

import org.scalatest.exceptions.{TestFailedException, TestPendingException}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate
import scala.xml.XML

/** The test support's own contract: defect tests, normalisation, the database and the XSD helper. */
class TestSupportSpec extends AnyWordSpec with Matchers {
  import KnownError.knownError

  "knownError" should {
    "report a still failing defect test as pending" in {
      a[TestPendingException] should be thrownBy knownError("x")(assert(1 == 2))
    }
    "fail once the defect is fixed, naming the id" in {
      val e = the[TestFailedException] thrownBy knownError("261005-ca4")(assert(1 == 1))
      e.getMessage should include ("261005-ca4")
    }
  }

  "Normalise" should {
    "replace ids, creation time and clock dates by tokens and keep everything else" in {
      val doc = XML.loadString(
        """<a:Doc xmlns:a="http://www.ebutilities.at/schemata/x/01p10"><a:MessageId>M1</a:MessageId>
          |<a:DocumentCreationDateTime>2026-10-08T13:00:00+02:00</a:DocumentCreationDateTime>
          |<a:ProcessDate>2026-10-09</a:ProcessDate><a:DateFrom>2099-01-01</a:DateFrom></a:Doc>""".stripMargin)
      val out = Normalise.document(doc, Normalise.dateTokens(LocalDate.of(2026, 10, 8)))
      out should include ("<x:MessageId>@MESSAGE_ID@</x:MessageId>")
      out should include ("@CREATED@")
      out should include ("<x:ProcessDate>@TOMORROW@</x:ProcessDate>")
      out should include ("<x:DateFrom>2099-01-01</x:DateFrom>")
    }
  }

  "TestDb" should {
    "carry the production migrations and the backend's base.eeg" in {
      TestDb.count("SELECT count(*) FROM eda.flyway_schema_history WHERE success") should be >= 2
      TestDb.count("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'base' AND table_name = 'eeg' AND column_name = 'communityId'") shouldBe 1
    }
  }

  "Xsd" should {
    "reject a document that is not valid" in {
      an[org.xml.sax.SAXParseException] should be thrownBy Xsd.validate(<CMRequest/>, "CMRequest_01p30.xsd")
    }
  }
}
