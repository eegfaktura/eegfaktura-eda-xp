package at.energydash.domain.eda

import at.energydash.domain.EbMsMessage
import at.energydash.domain.xml.CMRequestV0120Document
import cmrequest.v01p20.CMRequest
import ecmplist.v01p10._
import ponton.`package`.Cmrequestv01p20_CMRequestFormat
import scalaxb.CanWriteXML

import scala.xml.{NamespaceBinding, Node}

case class CMRequestOfflineRegistration(message: EbMsMessage) extends EdaMessage {
  override def getVersion(version: Option[String] = None): EdaXMLMessage[_] = CMRequestOfflineRegistrationXMLMessage(message)
}

case class CMRequestOfflineRegistrationXMLMessage(message: EbMsMessage) extends EdaXMLMessage[cmrequest.v01p20.CMRequest] {
  override implicit val edaTypeCanWrite: CanWriteXML[CMRequest] = Cmrequestv01p20_CMRequestFormat

  override def rootNodeLabel: Option[String] = Some("CMRequest")

  override def schemaLocation: Option[String] =
    Some("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p10 http://www.ebutilities.at/schemata/customerprocesses/EC_REQ_OFF/01.00/ANFORDERUNG_ECOF")

  override def toDoc: cmrequest.v01p20.CMRequest = CMRequestV0120Document(message).toDoc

  override def toScope: NamespaceBinding = scalaxb.toScope(
    None -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("ns2") -> "http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p10",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance"
  )

  override def toXML: Node = {
    scalaxb.toXML[cmrequest.v01p20.CMRequest](
      toDoc,
      Some("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p10"),
      rootNodeLabel,
      toScope,
      true).head
  }
}

//object CMRequestOfflineRegistrationXMLMessage extends EdaResponseType {
//  def fromXML(xmlFile: Elem): Try[CMRequestOfflineRegistration] = {
//    resolveMessageCode(xmlFile) match {
//      case Success(mc) => mc match {
//        case EbMsMessageType.OFFLINE_REG_COMPLETION => Try(scalaxb.fromXML[ECMPList](xmlFile)).map(document =>
//          CMRequestOfflineRegistration(
//            EbMsMessage(
//              messageId = Some(document.ProcessDirectory.MessageId),
//              conversationId = document.ProcessDirectory.ConversationId,
//              sender = document.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
//              receiver = document.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
//              messageCode = EbMsMessageType.withName(document.MarketParticipantDirectory.MessageCode.toString),
//              messageCodeVersion = Some("01.00"),
//              meterList = Some(document.ProcessDirectory.MPListData
//                .map(mp =>
//                  Meter(
//                    mp.MeteringPoint,
//                    Some(MeterDirectionType.withName(mp.MPTimeData.head.EnergyDirection.toString))
//                  )
//                )
//              ),
//            )
//          )
//        )
//        case _ => Try(scalaxb.fromXML[CMNotification](xmlFile)).map(document =>
//          CMRequestOfflineRegistration(
//            EbMsMessage(
//              messageId=Some(document.ProcessDirectory.MessageId),
//              conversationId=document.ProcessDirectory.ConversationId,
//              sender=document.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
//              receiver=document.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
//              messageCode=EbMsMessageType.withName(document.MarketParticipantDirectory.MessageCode),
//              messageCodeVersion=Some("01.11"),
//              requestId=Some(document.ProcessDirectory.CMRequestId),
//              responseData=Some(document.ProcessDirectory.ResponseData.map(r => ResponseData(r.MeteringPoint, r.ResponseCode))),
//            )
//          )
//        )
//      }
//      case Failure(exception) =>
//        Try(CMRequestOfflineRegistration(
//          EbMsMessage(
//            messageCode = EbMsMessageType.ERROR_MESSAGE,
//            messageCodeVersion=Some("01.00"),
//            conversationId = "1",
//            messageId = None,
//            sender = "",
//            receiver = "",
//            errorMessage = Some(exception.getMessage)
//          )
//        ))
//    }
//  }
//}
