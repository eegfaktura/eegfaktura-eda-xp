package at.energydash.domain.eda

import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType
import scalaxb.CanWriteXML

import scala.util.Try
import scala.xml.{Elem, NamespaceBinding, Node, NodeSeq}

case class EdaErrorMessage(message: EbMsMessage) extends EdaMessage {

  override def getVersion(version: Option[String]): Try[EdaXMLMessage[_]] = Try(EdaErrorXMLMessage(message))
}


case class EdaErrorXMLMessage(message: EbMsMessage) extends EdaXMLMessage[String] {

  override implicit val edaTypeCanWrite: CanWriteXML[String] = scalaxb.XMLStandardTypes.__StringXMLFormat

  override def toDoc: String = "Eda Error"

  override def toScope: NamespaceBinding = scalaxb.toScope((None -> "" ))
  override def toXML: Node = NodeSeq.Empty.head

}

object EdaErrorXMLMessage extends EdaResponseType {
  def fromXML(xmlFile: Elem): Try[EdaErrorMessage] = {
    Try(EdaErrorMessage(
      EbMsMessage(
        messageCode = EbMsMessageType.ERROR_MESSAGE,
        messageCodeVersion = Some("01.00"),
        conversationId = "1",
        messageId = None,
        sender = "",
        receiver = "",
        errorMessage = Some((xmlFile \ "ReasonText").text)
      )
    ))
  }
}

object EdaWrongVersionXMLMessage extends EdaResponseType {
  def fromXML(xmlFile: Elem): Try[EdaErrorMessage] = {
    Try(EdaErrorMessage(
      EbMsMessage(
        messageCode = EbMsMessageType.ERROR_MESSAGE,
        messageCodeVersion = Some("01.00"),
        conversationId = "1",
        messageId = None,
        sender = "",
        receiver = "",
        errorMessage = Some("Wrong Process Version")
      ))
    )
  }
}