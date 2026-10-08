package at.energydash.domain.xml

import at.energydash.config.Config
import at.energydash.domain.eda.MessageHelper.{buildCalendar, buildCalendarDate, getNow, getProcessDate}
import at.energydash.domain.enums.{EbMsMessageType, EcDisModelEnum, EcTypeEnum, MeterDirectionType}
import at.energydash.domain.{EbMsMessage, Meter}
import ponton.`package`.{Commontypesv01p20_AddressTypeFormat, Commontypesv01p20_DocumentModeFormat, Ecmplistv01p20_SchemaVersionFormat, __BooleanXMLFormat}
import scalaxb.Helper

import java.util.Date

// ECMPList Schema 01p20 (gültig ab 2026-10, "Erweiterung ECType"): ECType wird
// optional + neue Werte SC/PP; zusätzlich werden mehrere MPTimeData-Felder
// optional (EnergyDirection, ECPartFact, ECShare) bzw. neu (DataType, Purpose,
// TechCode, FuelCode, ECZoneLevel), und ECID wird optional. PlantCategory entfällt.
// Eingehend: ZP-Listen-Antwort in 01p20 (toMessage). Ausgehend: ANFORDERUNG_CPF im
// Schema Set EC_PRTFACT_CHANGE_01.10 (ab 05.10.2026), siehe forPartitionChange.
class ECMPListV0120Document(doc: ecmplist.v01p20.ECMPList) {
  def toDoc: ecmplist.v01p20.ECMPList = doc

  def toMessage: EbMsMessage = {
    EbMsMessage(
      messageId = Some(doc.ProcessDirectory.MessageId),
      conversationId = doc.ProcessDirectory.ConversationId,
      sender = doc.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
      receiver = doc.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
      messageCode = EbMsMessageType.withName(doc.MarketParticipantDirectory.MessageCode.toString),
      messageCodeVersion = Some("02.00"),
      ecId = doc.ProcessDirectory.ECID,
      meterList = Some(doc.ProcessDirectory.MPListData
        .flatMap(m =>
          m.MPTimeData.map(mp =>
            Meter(
              meteringPoint = m.MeteringPoint,
              direction = mp.EnergyDirection.map(d => MeterDirectionType.withName(d.toString)),
              activation = Some(mp.DateActivate.toGregorianCalendar.getTime),
              partFact = mp.ECPartFact,
              from = Some(mp.DateFrom.toGregorianCalendar.getTime),
              to = Some(mp.DateTo.toGregorianCalendar.getTime),
              share = mp.ECShare,
              consentId = m.ConsentId
            ))
        )
      )
    )
  }
}

object ECMPListV0120Document {
  def apply(doc: ecmplist.v01p20.ECMPList) = new ECMPListV0120Document(doc)

  /** Pflichtfeld MPTimeData/DataType ("Datentypen der Anfrage", neu in 01p20). */
  val PartitionChangeDataType = "EnergyCommunityRegistration"

  /**
   * ANFORDERUNG_CPF in ECMPList 01p20. Inhaltlich wie der 01p10-Builder
   * (ECMPListV0110Document.withMeterList + withRestrictedProcessDate): gueltig ab morgen,
   * bis 2099-12-31, sofern kein Zeitraum mitkommt. ECZoneLevel bleibt leer (optional).
   */
  def forPartitionChange(message: EbMsMessage): ecmplist.v01p20.ECMPList = ecmplist.v01p20.ECMPList(
    MarketParticipantDirectory = ecmplist.v01p20.MarketParticipantDirectory(
      RoutingHeader = commontypes.v01p20.RoutingHeader(
        commontypes.v01p20.RoutingAddress(message.sender, Map(("@AddressType", scalaxb.DataRecord[commontypes.v01p20.AddressType](commontypes.v01p20.ECNumber)))),
        commontypes.v01p20.RoutingAddress(message.receiver, Map(("@AddressType", scalaxb.DataRecord[commontypes.v01p20.AddressType](commontypes.v01p20.ECNumber)))),
        at.energydash.domain.eda.MessageHelper.xmlDateTime(new Date)
      ),
      Sector = commontypes.v01p20.Number01,
      MessageCode = message.messageCode.toString,
      attributes = Map(
        ("@DocumentMode", scalaxb.DataRecord[commontypes.v01p20.DocumentMode](Config.interfaceMode match {
          case "SIMU" => commontypes.v01p20.SIMU
          case _ => commontypes.v01p20.PROD
        })),
        ("@Duplicate", scalaxb.DataRecord(false)),
        ("@SchemaVersion", scalaxb.DataRecord[ecmplist.v01p20.SchemaVersion](ecmplist.v01p20.Number01u4620)),
      )
    ),
    ProcessDirectory = ecmplist.v01p20.ProcessDirectory(
      MessageId = message.messageId.get,
      ConversationId = message.conversationId,
      ProcessDate = Helper.toCalendar(getProcessDate),
      ECID = Some(message.ecId.getOrElse(throw new IllegalArgumentException("ANFORDERUNG_CPF without ecId"))),
      ECType = Some(message.ecType match {
        case Some(EcTypeEnum.GEA) => ecmplist.v01p20.GC
        case Some(EcTypeEnum.REGIONAL) => ecmplist.v01p20.RC_R
        case Some(EcTypeEnum.BEG) => ecmplist.v01p20.CC
        case _ => ecmplist.v01p20.RC_L
      }),
      ECDisModel = Some(message.ecDisModel match {
        case Some(EcDisModelEnum.STATIC) => ecmplist.v01p20.S
        case _ => ecmplist.v01p20.D
      }),
      MPListData = message.meterList.getOrElse(Nil).map(m => ecmplist.v01p20.MPListData(
        MeteringPoint = m.meteringPoint,
        MPTimeData = Seq(ecmplist.v01p20.MPTimeData(
          DateFrom = Helper.toCalendar(getNow(Some(1)).toString),
          DateTo = Helper.toCalendar(m.to.map(buildCalendarDate).getOrElse("2099-12-31")),
          EnergyDirection = Some(m.direction match {
            case Some(MeterDirectionType.CONSUMPTION) => ecmplist.v01p20.CONSUMPTION
            case _ => ecmplist.v01p20.GENERATION
          }),
          ECPartFact = Some(m.partFact.getOrElse(throw new IllegalArgumentException(s"ANFORDERUNG_CPF without participation factor for ${m.meteringPoint}"))),
          DataType = PartitionChangeDataType,
          DateActivate = Helper.toCalendar(buildCalendarDate(m.activation.get)),
        ))
      ))
    )
  )
}
