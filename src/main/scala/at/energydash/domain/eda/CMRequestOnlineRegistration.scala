package at.energydash.domain.eda

import at.energydash.domain.EbMsMessage
import at.energydash.domain.xml.{CMRequestV0110Document, CMRequestV0120Document}
import ponton.`package`._
import scalaxb.CanWriteXML

import scala.xml.{NamespaceBinding, Node}


case class CMRequestRegistrationOnline(message: EbMsMessage) extends EdaMessage {
  override def getVersion(version: Option[String] = None): EdaXMLMessage[_] = message.messageCodeVersion match {
    case Some("02.00") => CMRequestRegistrationOnlineXMLMessageV0200(message)
    case _ => CMRequestRegistrationOnlineXMLMessageV0110(message)
  }
}

case class CMRequestRegistrationOnlineXMLMessageV0200(message: EbMsMessage) extends EdaXMLMessage[cmrequest.v01p20.CMRequest] {
  override implicit val edaTypeCanWrite: CanWriteXML[cmrequest.v01p20.CMRequest] = Cmrequestv01p20_CMRequestFormat

  override def rootNodeLabel: Option[String] = Some("ns2:CMRequest")

  override def schemaLocation: Option[String] =
    Some("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p20 http://www.ebutilities.at/schemata/customerprocesses/EC_REQ_ONL/02.00/ANFORDERUNG_ECON")

  override def toDoc: cmrequest.v01p20.CMRequest = CMRequestV0120Document(message).toDoc

  override def toScope: NamespaceBinding = scalaxb.toScope(
//    None -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("ns2") -> "http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p20",
    Some("ct") -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance"
  )

  override def toXML: Node = {
    scalaxb.toXML[cmrequest.v01p20.CMRequest](toDoc, Some("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p20"), rootNodeLabel,
      toScope,
      true).head
  }
}

case class CMRequestRegistrationOnlineXMLMessageV0110(message: EbMsMessage) extends EdaXMLMessage[cmrequest.v01p10.CMRequest] {
  override implicit val edaTypeCanWrite: CanWriteXML[cmrequest.v01p10.CMRequest] = Cmrequestv01p10_CMRequestFormat
  override def rootNodeLabel: Option[String] = Some("ns2:CMRequest")

  override def schemaLocation: Option[String] =
    Some("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p10 http://www.ebutilities.at/schemata/customerprocesses/EC_REQ_ONL/01.00/ANFORDERUNG_ECON")

  override def toDoc: cmrequest.v01p10.CMRequest = CMRequestV0110Document(message).toDoc

  override def toScope: NamespaceBinding = scalaxb.toScope(
    None -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("ns2") -> "http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p10",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance"
  )

  override def toXML: Node = {
    scalaxb.toXML[cmrequest.v01p10.CMRequest](toDoc, Some("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p10"), rootNodeLabel,
      toScope,
      true).head
  }
}


//object CMRequestRegistrationOnlineXMLMessageV0110 extends EdaResponseType {
//  def fromXML(xmlFile: Elem): Try[CMRequestRegistrationOnline] = {
//    resolveMessageCode(xmlFile) match {
//      case Success(mc) => mc match {
//        case EbMsMessageType.ONLINE_REG_COMPLETION => Try(scalaxb.fromXML[ecmplist.v01p00.ECMPList](xmlFile)).map(document =>
//          CMRequestRegistrationOnline(
//            EbMsMessage(
//              messageId = Some(document.ProcessDirectory.MessageId),
//              conversationId = document.ProcessDirectory.ConversationId,
//              sender = document.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
//              receiver = document.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
//              messageCode = EbMsMessageType.withName(document.MarketParticipantDirectory.MessageCode.toString),
//              messageCodeVersion = Some("01.00"),
//              meterList = Some(document.ProcessDirectory.MPListData
//                .flatMap(m =>
//                  m.MPTimeData.map(mp =>
//                    Meter(
//                      meteringPoint=m.MeteringPoint,
//                      direction=Some(MeterDirectionType.withName(mp.EnergyDirection.toString)),
//                      activation=Some(mp.DateActivate.toGregorianCalendar.getTime),
//                    ))
//                )
//              ),
//            )
//          )
//        )
//        case _ => Try(scalaxb.fromXML[CMNotification](xmlFile)).map(document =>
//          CMRequestRegistrationOnline(
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
//        Try(CMRequestRegistrationOnline(
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
//
//object CMRequestRegistrationOnlineXMLMessageV0200 extends EdaResponseType {
//  def fromXML(xmlFile: Elem): Try[CMRequestRegistrationOnline] = {
//    resolveMessageCode(xmlFile) match {
//      case Success(mc) => mc match {
//        case EbMsMessageType.ONLINE_REG_COMPLETION => Try(scalaxb.fromXML[ecmplist.v01p10.ECMPList](xmlFile)).map(document =>
//          CMRequestRegistrationOnline(
//            EbMsMessage(
//              messageId = Some(document.ProcessDirectory.MessageId),
//              conversationId = document.ProcessDirectory.ConversationId,
//              sender = document.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
//              receiver = document.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
//              messageCode = EbMsMessageType.withName(document.MarketParticipantDirectory.MessageCode.toString),
//              messageCodeVersion = Some("02.00"),
//              meterList = Some(document.ProcessDirectory.MPListData
//                .flatMap(m =>
//                  m.MPTimeData.map(mp =>
//                    Meter(
//                      meteringPoint=m.MeteringPoint,
//                      direction=Some(MeterDirectionType.withName(mp.EnergyDirection.toString)),
//                      activation=Some(mp.DateActivate.toGregorianCalendar.getTime),
//                      partFact=Some(mp.ECPartFact)
//                    ))
//                )
//              ),
//            )
//          )
//        )
//        case _ => Try(scalaxb.fromXML[CMNotification](xmlFile)).map(document =>
//          CMRequestRegistrationOnline(
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
//        Try(CMRequestRegistrationOnline(
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