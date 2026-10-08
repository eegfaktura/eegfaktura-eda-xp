package at.energydash.domain.eda

import at.energydash.domain.enums.EbMsMessageType
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PontonHeaderMessageTypeSpec extends AnyWordSpec with Matchers {
  import MessageHelper.pontonHeaderMessageType

  "pontonHeaderMessageType" should {
    "send the schema set message type for requests on the new schema sets" in {
      pontonHeaderMessageType(EbMsMessageType.ZP_LIST) shouldBe "ANFORDERUNG_ECP"
      pontonHeaderMessageType(EbMsMessageType.ONLINE_REG_INIT) shouldBe "ANFORDERUNG_ECON"
      pontonHeaderMessageType(EbMsMessageType.OFFLINE_REG_INIT) shouldBe "ANFORDERUNG_ECOF"
      pontonHeaderMessageType(EbMsMessageType.CHANGE_METER_PARTITION) shouldBe "ANFORDERUNG_CPF"
    }
    "keep the process code for all other requests" in {
      pontonHeaderMessageType(EbMsMessageType.ENERGY_SYNC_REQ) shouldBe "CR_REQ_PT"
      pontonHeaderMessageType(EbMsMessageType.EDA_MSG_AUFHEBUNG_CCMS) shouldBe "CM_REV_SP"
    }
  }
}
