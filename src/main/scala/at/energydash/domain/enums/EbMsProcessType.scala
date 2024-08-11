package at.energydash.domain.enums

import io.circe.{Decoder, Encoder}

object EbMsProcessType extends Enumeration {
    type EbMsProcessType = Value

    implicit val decoder: Decoder[Value] = Decoder.decodeEnumeration(this)
    implicit val encoder: Encoder[Value] = Encoder.encodeEnumeration(this)

    val PROCESS_ENERGY_RESPONSE: EbMsProcessType.Value = Value("CR_MSG")
    val PROCESS_REGISTER_ONLINE: EbMsProcessType.Value = Value("EC_REQ_ONL")
    val PROCESS_REGISTER_OFFLINE: EbMsProcessType.Value = Value("EC_REQ_OFF")
    val PROCESS_LIST_METERINGPOINTS: EbMsProcessType.Value = Value("EC_PODLIST")
    val PROCESS_METERINGPOINTS_VALUE: EbMsProcessType.Value = Value("CR_REQ_PT")
    val PROCESS_REVOKE_VALUE: EbMsProcessType.Value = Value("CM_REV_IMP")
    val PROCESS_REVOKE_CUS: EbMsProcessType.Value = Value("CM_REV_CUS")
    val PROCESS_REVOKE_SP: EbMsProcessType.Value = Value("CM_REV_SP")
    val PROCESS_EC_PRTFACT_CHANGE: EbMsProcessType.Value = Value("EC_PRTFACT_CHANGE")
    val PROCESS_MASTER_DATA: EbMsProcessType.Value = Value("MD_REQ_GN")
    val PROCESS_UNKNOWN: EbMsProcessType.Value = Value("UNKNOWN")
//    val PROCESS_BASE_DATA: EbMsProcessType.Value = Value("EC_PODLIST")
}
