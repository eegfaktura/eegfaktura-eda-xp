package at.energydash.logging

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

// 261005-ca14: scalaxb's ParserFailure carries the whole document in its message
class LogTextSpec extends AnyWordSpec with Matchers {

  private val failure =
    """Error while parsing <v320:Message><Meter>AT0030000000000000000000000526178</Meter></v320:Message>: parser error "'{ns}CPRequest' expected but EDASendError found" while parsing /{ns}Message/EDASendError"""

  "errorText" should {
    "drop the document of a scalaxb parser failure and keep the reason" in {
      val text = LogText.errorText(failure)
      text should not include "AT0030000000000000000000000526178"
      text should not include "<"
      text shouldBe """Error while parsing the document: parser error "'{ns}CPRequest' expected but EDASendError found" while parsing /{ns}Message/EDASendError"""
    }
    "drop the document when the reason cannot be found" in {
      LogText.errorText("Error while parsing <a>secret</a>") shouldBe "Error while parsing the document (content not logged)"
    }
    "keep any other message" in {
      LogText.errorText("Connection refused") shouldBe "Connection refused"
      LogText.errorText(null: String) shouldBe ""
    }
    "name the exception class" in {
      LogText.errorText(new IllegalStateException(failure)) should startWith("java.lang.IllegalStateException: Error while parsing the document: parser error")
    }
  }
}
