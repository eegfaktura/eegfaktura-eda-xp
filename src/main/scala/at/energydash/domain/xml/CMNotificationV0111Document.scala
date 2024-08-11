package at.energydash.domain.xml

import at.energydash.domain.enums.EbMsMessageType
import at.energydash.domain.{EbMsMessage, ResponseData}
import cmnotification.v01p11.CMNotification

class CMNotificationV0111Document(doc: CMNotification) {
  def toDoc(): CMNotification = doc
  def toMessage: EbMsMessage = EbMsMessage(
    messageId=Some(doc.ProcessDirectory.MessageId),
    conversationId=doc.ProcessDirectory.ConversationId,
    sender=doc.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
    receiver=doc.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
    messageCode=EbMsMessageType.withName(doc.MarketParticipantDirectory.MessageCode),
    messageCodeVersion=Some("01.11"),
    requestId=Some(doc.ProcessDirectory.CMRequestId),
    responseData=Some(doc.ProcessDirectory.ResponseData.map(r => ResponseData(r.MeteringPoint, r.ResponseCode))),
  )
}


object CMNotificationV0111Document {
  def apply(doc: CMNotification): CMNotificationV0111Document = new CMNotificationV0111Document(doc)
}