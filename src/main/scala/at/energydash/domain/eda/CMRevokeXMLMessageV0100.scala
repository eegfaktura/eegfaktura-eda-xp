package at.energydash.domain.eda

import at.energydash.domain.EbMsMessage
import at.energydash.domain.xml.CMRevokeV0100Document
import ponton.`package`.Cmrevokev01p00_CMRevokeFormat
import scalaxb.CanWriteXML

import scala.util.Try
import scala.xml.{NamespaceBinding, Node}

case class CMRevokeMessage(message: EbMsMessage) extends EdaMessage {
  override def getVersion(version: Option[String]=None): Try[EdaXMLMessage[_]] = Try(CMRevokeXMLMessageV0100(message))
}

case class CMRevokeXMLMessageV0100(message: EbMsMessage) extends EdaXMLMessage[cmrevoke.v01p00.CMRevoke] {
  override implicit val edaTypeCanWrite: CanWriteXML[cmrevoke.v01p00.CMRevoke] = Cmrevokev01p00_CMRevokeFormat

  override def rootNodeLabel: Some[String] = Some("CMRevoke")

  override def schemaLocation: Option[String] =
    Some("http://www.ebutilities.at/schemata/customerconsent/cmrevoke/01p00 " +
      "http://www.ebutilities.at/schemata/customerprocesses/CM_REV_IMP/01.00/AUFHEBUNG_CCMI")

  override def toDoc: cmrevoke.v01p00.CMRevoke = CMRevokeV0100Document(message).toDoc

  override def toScope: NamespaceBinding = scalaxb.toScope(
    None -> "http://www.ebutilities.at/schemata/customerconsent/cmrevoke/01p00",
    Some("ct") -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance",
  )

  def toXML: Node = {
    scalaxb.toXML[cmrevoke.v01p00.CMRevoke](toDoc, schemaLocation, rootNodeLabel,
      toScope,
      true).head

//    DataRecord.toXML(DataRecord(toDoc), None, rootNodeLabel, toScope, typeAttribute = true).head
//    xml.NodeSeq.Empty.head
  }
}

//object CMRevokeXMLMessageV0100 extends EdaResponseType {
//  override def fromXML(xmlFile: Elem): Try[CMRevokeMessage] = {
//    Try(scalaxb.fromXML[cmrevoke.v01p00.CMRevoke](xmlFile)).map(document => {
//      CMRevokeMessage(
//        EbMsMessage(
//          messageId = Some(document.ProcessDirectory.MessageId),
//          conversationId = document.ProcessDirectory.ConversationId,
//          sender = document.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
//          receiver = document.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
//          messageCode = EbMsMessageType.withName(document.MarketParticipantDirectory.MessageCode.toString),
//          messageCodeVersion = Some("01.00"),
//          responseData = Some(List(ResponseData(
//            Some(document.ProcessDirectory.MeteringPoint),
//            List(1099),
//            Some(document.ProcessDirectory.ConsentEnd.toGregorianCalendar().getTime.getTime))))
//        )
//      )
//    }
//    )
//  }
//}