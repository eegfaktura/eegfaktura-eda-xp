package at.energydash.actors.http

import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType
import org.apache.pekko.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

// platform#111 ca11: inbound messages without a process mapping must not fail the Ponton request
class PontonRouteSpec extends AnyWordSpec with Matchers {

  private def msg(code: EbMsMessageType.Value, error: Option[String] = None) =
    EbMsMessage(conversationId = "C1", sender = "AT003000", receiver = "RC100001", messageCode = code, errorMessage = error)

  "notificationFor" should {
    "use the process code for known message codes" in {
      val n = PontonRoute.notificationFor(msg(EbMsMessageType.EDA_MSG_AUFHEBUNG_CCMI))
      n.protocol shouldBe "CM_REV_IMP"
      n.message.messageCode shouldBe EbMsMessageType.EDA_MSG_AUFHEBUNG_CCMI
    }
    "send an unknown message type to the error protocol and keep its error text" in {
      val n = PontonRoute.notificationFor(msg(EbMsMessageType.ERROR_MESSAGE, Some("Unknown or not registered MessageType X")))
      n.protocol shouldBe PontonRoute.ErrorProtocol
      n.message.receiver shouldBe "RC100001"
      n.message.errorMessage shouldBe Some("Unknown or not registered MessageType X")
    }
    "name the message code when there is no error text" in {
      val n = PontonRoute.notificationFor(msg(EbMsMessageType.ERROR_MESSAGE))
      n.protocol shouldBe PontonRoute.ErrorProtocol
      n.message.errorMessage.get should include("No process for message code")
    }
  }

  "logBody" should {
    "log a short body in full" in {
      PontonRoute.logBody(ByteString("<Notification/>")) shouldBe "<Notification/>"
    }
    "cut a long body and name its size" in {
      val body = ByteString("<a>" + ("x" * (2 * 1024 * 1024)) + "</a>")
      val logged = PontonRoute.logBody(body)
      logged should startWith("<a>xxx")
      logged should endWith(s"… (${body.length} bytes)")
      logged.length should be < PontonRoute.LogBodyChars + 40
    }
    "count characters, not bytes, for a body with multibyte characters" in {
      val exact = "ä" * PontonRoute.LogBodyChars // 2 bytes each
      PontonRoute.logBody(ByteString(exact)) shouldBe exact
      val longer = ByteString("ä" * (PontonRoute.LogBodyChars + 1))
      PontonRoute.logBody(longer) shouldBe s"$exact… (${longer.length} bytes)"
    }
  }
}
