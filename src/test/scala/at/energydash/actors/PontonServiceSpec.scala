package at.energydash.actors

import at.energydash.domain.enums.EbMsMessageType
import at.energydash.domain.{EbMsMessage, Meter}
import at.energydash.testsupport.FakePonton
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt

/** `PontonService` against the loopback FakePonton of `app.kepserver.url` — never a real messenger. */
class PontonServiceSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with BeforeAndAfterEach {

  private lazy val ponton = new FakePonton()(system)

  override def beforeEach(): Unit = ponton.reset()
  override def afterAll(): Unit = { ponton.stop(); super.afterAll() }

  private val revoke = EbMsMessage(messageId = Some("RC100401209901010000000000000001"), conversationId = "con-1",
    sender = "RC100401", receiver = "AT003000", messageCode = EbMsMessageType.EDA_MSG_AUFHEBUNG_CCMS,
    messageCodeVersion = Some("01.30"), requestId = Some("REQ1"),
    meter = Some(Meter("AT0030000000000000000000000401001", None, consentId = Some("AT003000209901010000000000000401"))))

  "Ponton Service Actor" should {
    "send a mapped message to Ponton and confirm it" in {
      val edaResponse = createTestProbe[EdaCommand]()
      spawn(PontonService()) ! SendEdaCommand(revoke, edaResponse.ref)

      edaResponse.expectMessage(5.seconds, SendEdaResponse(revoke))
      val sent = ponton.requests
      sent should have size 1
      sent.head.path shouldBe "/ponton/eda/webservice/outbound"
      sent.head.pontonHeader("MessageType") shouldBe "CM_REV_SP"
      sent.head.pontonHeader("MessageVersion") shouldBe "01.30"
      sent.head.pontonHeader("SenderId") shouldBe "RC100401"
      sent.head.pontonHeader("ReceiverId") shouldBe "AT003000"
    }

    "answer an error when Ponton refuses the request" in {
      ponton.script(FakePonton.Status(500))
      val edaResponse = createTestProbe[EdaCommand]()
      spawn(PontonService()) ! SendEdaCommand(revoke, edaResponse.ref)

      val error = edaResponse.expectMessageType[SendResponseError](5.seconds)
      error.tenant shouldBe "RC100401"
      error.receiver shouldBe "AT003000"
      error.step shouldBe "Send KEP"
      error.message should include ("500")
    }

    "answer an error and send nothing for a message type without a document" in {
      val edaResponse = createTestProbe[EdaCommand]()
      val answer = revoke.copy(messageCode = EbMsMessageType.OFFLINE_REG_ANSWER)
      spawn(PontonService()) ! SendEdaCommand(answer, edaResponse.ref)

      val error = edaResponse.expectMessageType[SendResponseError](5.seconds)
      error.step shouldBe "Send KEP"
      error.message should include ("No XML mapping")
      ponton.requests shouldBe empty
    }
  }
}
