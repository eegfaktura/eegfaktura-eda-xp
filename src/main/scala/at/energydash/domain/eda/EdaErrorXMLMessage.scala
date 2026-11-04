package at.energydash.domain.eda

import at.energydash.domain.EbMsMessage
import scalaxb.CanWriteXML

import scala.util.Try
import scala.xml.{NamespaceBinding, Node, NodeSeq}

case class EdaErrorMessage(message: EbMsMessage) extends EdaMessage {

  override def getVersion(version: Option[String]): Try[EdaXMLMessage[_]] = Try(EdaErrorXMLMessage(message))
}


case class EdaErrorXMLMessage(message: EbMsMessage) extends EdaXMLMessage[String] {

  override implicit val edaTypeCanWrite: CanWriteXML[String] = scalaxb.XMLStandardTypes.__StringXMLFormat

  override def toDoc: String = "Eda Error"

  override def toScope: NamespaceBinding = scalaxb.toScope((None -> "" ))
  override def toXML: Node = NodeSeq.Empty.head

}
