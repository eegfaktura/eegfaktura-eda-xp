package at.energydash.domain.xml

import at.energydash.domain.enums.{EbMsMessageType, MeterDirectionType}
import at.energydash.domain.{EbMsMessage, Meter}
import ecmplist.v01p00.ECMPList

case class ECMPListV0100Document(doc: ecmplist.v01p00.ECMPList) {
  def toDoc: ECMPList = doc

  def toMessage: EbMsMessage = {
    EbMsMessage(
      messageId = Some(doc.ProcessDirectory.MessageId),
      conversationId = doc.ProcessDirectory.ConversationId,
      sender = doc.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
      receiver = doc.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
      messageCode = EbMsMessageType.withName(doc.MarketParticipantDirectory.MessageCode.toString),
      messageCodeVersion = Some("01.00"),
      ecId = Some(doc.ProcessDirectory.ECID),
      meterList = Some(doc.ProcessDirectory.MPListData
        .flatMap(m =>
          m.MPTimeData.map(mp =>
            Meter(
              meteringPoint = m.MeteringPoint,
              direction = Some(MeterDirectionType.withName(mp.EnergyDirection.toString)),
              activation = Some(mp.DateActivate.toGregorianCalendar.getTime),
              from = Some(mp.DateFrom.toGregorianCalendar.getTime),
              to = Some(mp.DateTo.toGregorianCalendar.getTime),
              share = mp.ECShare,
              plantCategory = mp.PlantCategory,
            ))
        )
      )
    )
  }
}
