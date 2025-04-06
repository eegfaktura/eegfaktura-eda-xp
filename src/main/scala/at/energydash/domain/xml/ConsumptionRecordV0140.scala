package at.energydash.domain.xml

import at.energydash.domain.enums.EbMsMessageType
import at.energydash.domain._

class ConsumptionRecordV0140(doc: consumptionrecord.v01p40.ConsumptionRecord) {
  def toDoc: consumptionrecord.v01p40.ConsumptionRecord = doc
  def toMessage: EbMsMessage = EbMsMessage(
    messageId = Some(doc.ProcessDirectory.MessageId),
    conversationId = doc.ProcessDirectory.ConversationId,
    sender = doc.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
    receiver = doc.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
    messageCode = EbMsMessageType.withName(doc.MarketParticipantDirectory.MessageCode.toString),
    messageCodeVersion = Some("01.40"),
    ecId = doc.ProcessDirectory.ECID,
    meter = Some(Meter(doc.ProcessDirectory.MeteringPoint, None)),
    energy = Some(doc.ProcessDirectory.Energy.map(energy => Energy(
      start=energy.MeteringPeriodStart.toGregorianCalendar.getTime,
      end=energy.MeteringPeriodEnd.toGregorianCalendar.getTime,
      interval=energy.MeteringIntervall.toString,
      nInterval = energy.NumberOfMeteringIntervall,
      data=energy.EnergyData.map(v =>
        EnergyData(
          v.MeterCode,
          v.EP.map(vv => EnergyValue(
            from=vv.DTF.toGregorianCalendar.getTime,
            to=Some(vv.DTT.toGregorianCalendar.getTime),
            method=Some(vv.MM.toString),
            value=vv.BQ
          ))
        )
      )
    ))),
  )
}

object ConsumptionRecordV0140 {
  def apply(doc: consumptionrecord.v01p40.ConsumptionRecord): ConsumptionRecordV0140 = new ConsumptionRecordV0140(doc)
}
