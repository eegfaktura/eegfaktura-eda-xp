package at.energydash.domain.eda

import at.energydash.domain.EbMsMessage
import at.energydash.domain.xml.ECMPListV0110Document
import ponton.`package`.Ecmplistv01p10_ECMPListFormat
import scalaxb.CanWriteXML

import scala.util.Try
import scala.xml.{NamespaceBinding, Node}


case class ECPartitionChangeMessage(message: EbMsMessage) extends EdaMessage {
  override def getVersion(version: Option[String] = None): Try[EdaXMLMessage[_]] = Try(ECPartitionChangeXMLMessage(message))
}

case class ECPartitionChangeXMLMessage(message: EbMsMessage) extends EdaXMLMessage[ecmplist.v01p10.ECMPList] {

  override implicit val edaTypeCanWrite: CanWriteXML[ecmplist.v01p10.ECMPList] = Ecmplistv01p10_ECMPListFormat
  override def rootNodeLabel: Some[String] = Some("ECMPList")

  override def schemaLocation: Option[String] =
    Some("http://www.ebutilities.at/schemata/customerprocesses/ecmplist/01p10 " +
      "http://www.ebutilities.at/schemata/customerprocesses/EC_PRTFACT_CHANGE/01.00/ANFORDERUNG_CPF")

  override def toDoc: ecmplist.v01p10.ECMPList = ECMPListV0110Document(message)
    .withMeterList(message.meterList)
    .withRestrictedProcessDate()
    .toDoc

  override def toScope: NamespaceBinding = scalaxb.toScope(
//    Some("rv") -> "http://www.ebutilities.at/schemata/customerprocesses/ecmplist/01p10",
    None -> "http://www.ebutilities.at/schemata/customerprocesses/ecmplist/01p10",
    Some("ct") -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance",
  )

  override def toXML: Node = {
    scalaxb.toXML[ecmplist.v01p10.ECMPList](toDoc, schemaLocation, rootNodeLabel, toScope, true).head
  }

}

//object ECPartitionChangeXMLMessage extends EdaResponseType {
//  override def fromXML(xmlFile: Elem): Try[ECPartitionChangeMessage] =
//    Try(scalaxb.fromXML[CPNotification](xmlFile)).map(document =>
//      ECPartitionChangeMessage(
//        EbMsMessage(
//          messageId = Some(document.ProcessDirectory.MessageId),
//          conversationId = document.ProcessDirectory.ConversationId,
//          sender = document.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
//          receiver = document.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
//          messageCode = EbMsMessageType.withName(document.MarketParticipantDirectory.MessageCode.toString),
//          messageCodeVersion = Some("01.13"),
//          responseData = Some(document.ProcessDirectory.ResponseData.ResponseCode.map(r => ResponseData(None, List(r)))),
//        )
//      )
//    )
//}
