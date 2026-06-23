package at.energydash.domain.xml

import at.energydash.domain.{EbMsMessage, Energy, EnergyData, EnergyValue, Meter}
import at.energydash.domain.enums.EbMsMessageType

// ConsumptionRecord Schema 01p31 (CR-Prozessversion 03.03). Gegenüber 01p30 ist
// DTT verpflichtend (nicht mehr optional); MM bleibt optional. Ohne diese Variante
// würde ein eingehender CR im Namespace 01p31 in XmlParseHandler auf den
// ERROR_MESSAGE-Default fallen.
class ConsumptionRecordV0131(doc: consumptionrecord.v01p31.ConsumptionRecord) {
  def toDoc: consumptionrecord.v01p31.ConsumptionRecord = doc
  def toMessage: EbMsMessage = EbMsMessage(
    messageId = Some(doc.ProcessDirectory.MessageId),
    conversationId = doc.ProcessDirectory.ConversationId,
    sender = doc.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
    receiver = doc.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
    messageCode = EbMsMessageType.withName(doc.MarketParticipantDirectory.MessageCode.toString),
    messageCodeVersion = Some("01.31"),
    meter = Some(Meter(doc.ProcessDirectory.MeteringPoint, None)),
    energy = Some(doc.ProcessDirectory.Energy.map(energy => Energy(
      energy.MeteringPeriodStart.toGregorianCalendar.getTime,
      energy.MeteringPeriodEnd.toGregorianCalendar.getTime,
      energy.MeteringIntervall.toString,
      energy.NumberOfMeteringIntervall,
      data=energy.EnergyData.map(v =>
        EnergyData(
          v.MeterCode,
          v.EP.map(vv => EnergyValue(
            vv.DTF.toGregorianCalendar.getTime,
            Some(vv.DTT.toGregorianCalendar.getTime),
            vv.MM.map(mm => mm.toString),
            vv.BQ
          ))
        )
      )
    ))),
  )
}

object ConsumptionRecordV0131 {
  def apply(doc: consumptionrecord.v01p31.ConsumptionRecord): ConsumptionRecordV0131 = new ConsumptionRecordV0131(doc)
}
