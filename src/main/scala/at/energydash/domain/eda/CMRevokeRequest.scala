package at.energydash.domain.eda

import at.energydash.domain.EbMsMessage
import at.energydash.domain.xml.CMRevokeV0100Document
import cmnotification.v01p11._
import ponton.`package`.Cmrevokev01p00_CMRevokeFormat
import scalaxb.CanWriteXML

import scala.util.Try
import scala.xml.{NamespaceBinding, Node}

case class CMRevokeRequest(message: EbMsMessage) extends EdaMessage {
  override def getVersion(version: Option[String] = None): Try[EdaXMLMessage[_]] = Try(CMRevokeRequestV0100(message))
}

case class CMRevokeRequestV0100(message: EbMsMessage) extends EdaXMLMessage[cmrevoke.v01p00.CMRevoke] {
  override implicit val edaTypeCanWrite: CanWriteXML[cmrevoke.v01p00.CMRevoke] = Cmrevokev01p00_CMRevokeFormat

  override def rootNodeLabel: Some[String] = Some("CMRevoke")

  override def schemaLocation: Option[String] =
  Some("http://www.ebutilities.at/schemata/customerconsent/cmrevoke/01p00 " +
    "http://www.ebutilities.at/schemata/customerprocesses/CM_REV_SP/01.02/AUFHEBUNG_CCMS")

  override def toDoc: cmrevoke.v01p00.CMRevoke = CMRevokeV0100Document(message).toDoc

  override def toScope: NamespaceBinding = scalaxb.toScope(
    None -> "http://www.ebutilities.at/schemata/customerconsent/cmrevoke/01p00",
    Some("ct") -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance"
  )

  def toXML: Node = {
    scalaxb.toXML[cmrevoke.v01p00.CMRevoke](toDoc, schemaLocation, rootNodeLabel,
      toScope,
      true).head
  }
}