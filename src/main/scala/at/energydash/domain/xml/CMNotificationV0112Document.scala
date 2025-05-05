package at.energydash.domain.xml

import at.energydash.domain.enums.EbMsMessageType
import at.energydash.domain.{EbMsMessage, ResponseData}
import cmnotification.v01p12.CMNotification

class CMNotificationV0112Document(doc: CMNotification) {
  def toDoc(): CMNotification = doc
  def toMessage: EbMsMessage = EbMsMessage(
    messageId=Some(doc.ProcessDirectory.MessageId),
    conversationId=doc.ProcessDirectory.ConversationId,
    sender=doc.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
    receiver=doc.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
    messageCode=EbMsMessageType.withName(doc.MarketParticipantDirectory.MessageCode),
    messageCodeVersion=Some("01.12"),
    requestId=Some(doc.ProcessDirectory.CMRequestId),
    responseData=Some(doc.ProcessDirectory.ResponseData.map(r => ResponseData(
      MeteringPoint=r.MeteringPoint,
      ResponseCode=r.ResponseCode,
      ConsentId=r.ConsentId,
    ))),
  )
}


object CMNotificationV0112Document {
  def apply(doc: CMNotification): CMNotificationV0112Document = new CMNotificationV0112Document(doc)
}