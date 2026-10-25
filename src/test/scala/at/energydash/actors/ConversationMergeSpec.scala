package at.energydash.actors

import at.energydash.actors.ConversationEntity.mergeEbmsMessage
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.domain.enums.EbMsMessageType._
import at.energydash.domain.{EbMsMessage, Meter}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.Date

/** Which fields of the stored request an answer takes over (`ConversationEntity.mergeEbmsMessage`). */
class ConversationMergeSpec extends AnyWordSpec with Matchers {
  private val meter = Meter("AT0030000000000000000000000401001", None)
  private val list = Seq(meter, Meter("AT0030000000000000000000000401002", None))
  private val stored = EbMsMessage(conversationId = "C", sender = "RC100401", receiver = "AT003000",
    messageCode = ONLINE_REG_INIT, meter = Some(meter), meterList = Some(list), ecId = Some("EC-STORED"),
    consentEnd = Some(new Date(4102354800000L)))
  private def answer(code: EbMsMessageType.Value) = EbMsMessage(conversationId = "C", sender = "AT003000",
    receiver = "RC100401", messageCode = code, ecId = Some("EC-ANSWER"))

  "mergeEbmsMessage" should {
    "take meter and ecId for PT answers" in {
      Seq(ENERGY_SYNC_RES, ENERGY_SYNC_REJECTION).foreach { c =>
        val m = mergeEbmsMessage(Some(stored), answer(c))
        (m.meter, m.ecId) shouldBe ((Some(meter), Some("EC-STORED")))
      }
    }
    "take consent end and ecId for CCMS answers" in {
      Seq(EDA_MSG_ANTWORT_CCMS, EDA_MSG_ABLEHNUNG_CCMS).foreach { c =>
        val m = mergeEbmsMessage(Some(stored), answer(c))
        (m.consentEnd, m.ecId) shouldBe ((stored.consentEnd, Some("EC-STORED")))
      }
    }
    "take the meter list and ecId for CPF answers" in {
      Seq(CHANGE_METER_PARTITION_ANSWER, CHANGE_METER_PARTITION_REJECTION).foreach { c =>
        val m = mergeEbmsMessage(Some(stored), answer(c))
        (m.meterList, m.ecId) shouldBe ((Some(list), Some("EC-STORED")))
      }
    }
    "take the stored ecId for registration answers and the metering-point list, if there is one" in {
      Seq(ONLINE_REG_ANSWER, ONLINE_REG_REJECTION, ONLINE_REG_APPROVAL, ONLINE_REG_COMPLETION, OFFLINE_REG_ANSWER,
        OFFLINE_REG_REJECTION, OFFLINE_REG_APPROVAL, OFFLINE_REG_COMPLETION, ZP_LIST_RESPONSE).foreach { c =>
        mergeEbmsMessage(Some(stored), answer(c)).ecId shouldBe Some("EC-STORED")
        mergeEbmsMessage(Some(stored.copy(ecId = None)), answer(c)).ecId shouldBe Some("EC-ANSWER")
      }
    }
    "leave every other message unchanged, also without a stored request" in {
      Seq(ENERGY_FILE_RESPONSE, ONLINE_REG_ABORT, EDA_MSG_AUFHEBUNG_CCMI, ZP_LIST_REJECTION).foreach { c =>
        mergeEbmsMessage(Some(stored), answer(c)) shouldBe answer(c)
      }
      mergeEbmsMessage(None, answer(ZP_LIST_RESPONSE)) shouldBe answer(ZP_LIST_RESPONSE)
    }
    "drop the answer's own ecId for PT answers when nothing is stored (pinned)" in {
      mergeEbmsMessage(None, answer(ENERGY_SYNC_RES)).ecId shouldBe None
    }
  }
}
