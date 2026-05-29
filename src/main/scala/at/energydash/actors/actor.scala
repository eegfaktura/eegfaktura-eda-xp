package at.energydash

import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType

package object actors {
  def mergeEbmsMessage(stored: Option[EbMsMessage], current: EbMsMessage): EbMsMessage = {
    current.messageCode match {
      case EbMsMessageType.ENERGY_SYNC_REJECTION | EbMsMessageType.ENERGY_SYNC_RES =>
        current.copy(meter=stored.flatMap(_.meter), ecId = stored.flatMap(_.ecId))
      case EbMsMessageType.EDA_MSG_ABLEHNUNG_CCMS | EbMsMessageType.EDA_MSG_ANTWORT_CCMS =>
        current.copy(consentEnd = stored.flatMap(_.consentEnd), ecId = stored.flatMap(_.ecId))
      case EbMsMessageType.CHANGE_METER_PARTITION_ANSWER | EbMsMessageType.CHANGE_METER_PARTITION_REJECTION =>
        current.copy(meterList = stored.flatMap(_.meterList), ecId = stored.flatMap(_.ecId))
      case EbMsMessageType.ONLINE_REG_ANSWER | EbMsMessageType.ONLINE_REG_ABORT | EbMsMessageType.ONLINE_REG_REJECTION | EbMsMessageType.ONLINE_REG_APPROVAL | EbMsMessageType.ONLINE_REG_COMPLETION |
           EbMsMessageType.OFFLINE_REG_ANSWER | EbMsMessageType.OFFLINE_REG_ABORT | EbMsMessageType.OFFLINE_REG_REJECTION | EbMsMessageType.OFFLINE_REG_APPROVAL | EbMsMessageType.OFFLINE_REG_COMPLETION if stored.flatMap(_.ecId).isDefined =>
        current.copy(ecId = stored.flatMap(_.ecId))
      case EbMsMessageType.ZP_LIST_RESPONSE if stored.flatMap(_.ecId).isDefined =>
        current.copy(ecId = stored.flatMap(_.ecId))
      case _ =>
        current
    }
  }
}
