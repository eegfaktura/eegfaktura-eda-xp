package at.energydash.domain.eda

import at.energydash.domain.EbMsMessage
import at.energydash.domain.xml.CMRequestV0120Document
import cmrequest.v01p20.CMRequest
import ecmplist.v01p10._
import ponton.`package`.Cmrequestv01p20_CMRequestFormat
import scalaxb.CanWriteXML

import scala.util.Try
import scala.xml.{NamespaceBinding, Node}

case class CMRequestOfflineRegistration(message: EbMsMessage) extends EdaMessage {
  override def getVersion(version: Option[String] = None): Try[EdaXMLMessage[_]] = Try(CMRequestOfflineRegistrationXMLMessage(message))
}

case class CMRequestOfflineRegistrationXMLMessage(message: EbMsMessage) extends EdaXMLMessage[cmrequest.v01p20.CMRequest] {
  override implicit val edaTypeCanWrite: CanWriteXML[CMRequest] = Cmrequestv01p20_CMRequestFormat

  override def rootNodeLabel: Option[String] = Some("CMRequest")

  override def schemaLocation: Option[String] =
    Some("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p20 http://www.ebutilities.at/schemata/customerprocesses/EC_REQ_OFF/02.00/ANFORDERUNG_ECOF")

  override def toDoc: cmrequest.v01p20.CMRequest = CMRequestV0120Document(message).toDoc

  override def toScope: NamespaceBinding = scalaxb.toScope(
    None -> "http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p20",
    Some("ct") -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance"
  )

  override def toXML: Node = {
    scalaxb.toXML[cmrequest.v01p20.CMRequest](
      toDoc,
      schemaLocation,
      rootNodeLabel,
      toScope,
      true).head
  }
}