package at.energydash.domain.eda

import org.apache.pekko.util.ByteString
import at.energydash.domain.EbMsMessage
import at.energydash.domain.xml.CPRequestV0112Document
import ponton.`package`.Cprequestv01p12_CPRequestFormat
import scalaxb.CanWriteXML

import java.io.StringWriter
import scala.util.Try
import scala.xml.{NamespaceBinding, Node, XML}

case class CPRequestBaseData(message: EbMsMessage) extends EdaMessage {
  override def getVersion(version: Option[String] = None): Try[EdaXMLMessage[_]] = Try(CPRequestBaseDataXMLMessage(message))
}

case class CPRequestBaseDataXMLMessage(message: EbMsMessage) extends EdaXMLMessage[cprequest.v01p12.CPRequest] {
  override implicit val edaTypeCanWrite: CanWriteXML[cprequest.v01p12.CPRequest] = Cprequestv01p12_CPRequestFormat

  override def rootNodeLabel: Some[String] = Some("CPRequest")

  override def schemaLocation: Option[String] = Some("http://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12 " +
    "http://www.ebutilities.at/schemata/customerprocesses/MD_REQ_GN/03.12/ANFORDERUNG_GN")

  override def toDoc: cprequest.v01p12.CPRequest = CPRequestV0112Document(message).withExtension(Some(cprequest.v01p12.Extension(AssumptionOfCosts = false))).toDoc

  override def toScope: NamespaceBinding = scalaxb.toScope(
    None -> "http://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12",
    Some("ct") -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance",
  )

  def toXML: Node = {
    rewriteRootSchema(scalaxb.toXML[cprequest.v01p12.CPRequest](toDoc, Some("http://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12"), Some("CPRequest"),
      toScope,
      typeAttribute = false).head, "CPRequest",
      "http://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12 CPRequest_01p12.xsd")

  }

  override def toByte: Try[ByteString] = Try {
    val xml = toXML

    val xmlString = new StringWriter()
    XML.write(xmlString, xml, "UTF-8", true, null)

    ByteString.fromString(xmlString.toString)
  }
}

//object CPRequestBaseDataXMLMessage extends EdaResponseType {
//  def fromXML(xmlFile: Elem): Try[CPRequestBaseData] = {
//    Try(scalaxb.fromXML[cprequest.v01p12.CPRequest](xmlFile)).map(document =>
//      CPRequestBaseData(
//        EbMsMessage(
//          messageId = Some(document.ProcessDirectory.MessageId),
//          conversationId = document.ProcessDirectory.ConversationId,
//          sender = document.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
//          receiver = document.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
//          messageCode = EbMsMessageType.withName(document.MarketParticipantDirectory.MessageCode.toString),
//          messageCodeVersion = Some("01.12"),
//        )
//      )
//    )
//  }
//}