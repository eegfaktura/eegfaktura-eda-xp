package at.energydash.domain.xml

import at.energydash.domain.{EbMsMessage, Energy, EnergyData, EnergyValue, Meter}
import at.energydash.domain.enums.EbMsMessageType

class ConsumptionRecordV0130(doc: consumptionrecord.v01p30.ConsumptionRecord) {
  def toDoc: consumptionrecord.v01p30.ConsumptionRecord = doc
  def toMessage: EbMsMessage = EbMsMessage(
    messageId = Some(doc.ProcessDirectory.MessageId),
    conversationId = doc.ProcessDirectory.ConversationId,
    sender = doc.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
    receiver = doc.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
    messageCode = EbMsMessageType.withName(doc.MarketParticipantDirectory.MessageCode.toString),
    messageCodeVersion = Some("01.30"),
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
            vv.DTT.map(dtt=>dtt.toGregorianCalendar.getTime),
            vv.MM.map(mm => mm.toString),
            vv.BQ
          ))
        )
      )
    )).head
    ),
  )
}

object ConsumptionRecordV0130 {
  def apply(doc: consumptionrecord.v01p30.ConsumptionRecord): ConsumptionRecordV0130 = new ConsumptionRecordV0130(doc)
}
