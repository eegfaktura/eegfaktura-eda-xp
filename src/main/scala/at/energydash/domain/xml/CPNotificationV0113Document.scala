package at.energydash.domain.xml

import at.energydash.domain.{EbMsMessage, ResponseData}
import at.energydash.domain.enums.EbMsMessageType

class CPNotificationV0113Document(doc: cpnotification.v01p13.CPNotification) {
  def toDoc: cpnotification.v01p13.CPNotification = doc
  def toMessage: EbMsMessage = EbMsMessage(
    messageId = Some(doc.ProcessDirectory.MessageId),
    conversationId = doc.ProcessDirectory.ConversationId,
    sender = doc.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
    receiver = doc.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
    messageCode = EbMsMessageType.withName(doc.MarketParticipantDirectory.MessageCode),
    messageCodeVersion = Some("01.13"),
    responseData = Some(doc.ProcessDirectory.ResponseData.ResponseCode.map(r =>
      ResponseData(None, List(r)))),
  )
}

object CPNotificationV0113Document {
  def apply(doc: cpnotification.v01p13.CPNotification):CPNotificationV0113Document = new CPNotificationV0113Document(doc)
}
