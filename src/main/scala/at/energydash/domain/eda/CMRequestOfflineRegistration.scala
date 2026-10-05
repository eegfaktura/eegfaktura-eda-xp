package at.energydash.domain.eda

import at.energydash.domain.EbMsMessage
import at.energydash.domain.xml.{CMRequestV0120Document, CMRequestV0121Document, CMRequestV0130Document}
import cmrequest._
import ponton.`package`.{Cmrequestv01p20_CMRequestFormat, Cmrequestv01p21_CMRequestFormat, Cmrequestv01p30_CMRequestFormat}
import scalaxb.CanWriteXML

import scala.util.Try
import scala.xml.{NamespaceBinding, Node}

case class CMRequestOfflineRegistration(message: EbMsMessage) extends EdaMessage {
  override def getVersion(version: Option[String] = None): Try[EdaXMLMessage[_]] = message.messageCodeVersion match {
    case Some("02.00") => Try(CMRequestOfflineRegistrationXMLMessage(message))
    case Some("02.10") => Try(CMRequestOfflineRegistrationXMLMessageV0210(message))
    // 02.30 = Schemaset von EC_REQ_OFF 03.00 (ab 05.10.2026): gleiches XML (cmrequest 01p30),
    // nur Versionskennung im Ponton-Header und im schemaLocation-Pfad.
    case Some(v @ ("02.20" | "02.30")) => Try(CMRequestOfflineRegistrationXMLMessageV0220(message, v))
    case _ => fallbackVersion(CMRequestOfflineRegistrationXMLMessage(message))
  }
}

case class CMRequestOfflineRegistrationXMLMessageV0210(message: EbMsMessage) extends EdaXMLMessage[cmrequest.v01p21.CMRequest] {
  override implicit val edaTypeCanWrite: CanWriteXML[v01p21.CMRequest] = Cmrequestv01p21_CMRequestFormat

  override def rootNodeLabel: Option[String] = Some("CMRequest")

  override def schemaLocation: Option[String] =
    Some("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p21 " +
      "http://www.ebutilities.at/schemata/customerprocesses/EC_REQ_OFF/02.10/ANFORDERUNG_ECOF")

  override def toDoc: cmrequest.v01p21.CMRequest = CMRequestV0121Document(message).toDoc

  override def toScope: NamespaceBinding = scalaxb.toScope(
    None -> "http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p21",
    Some("ct") -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance"
  )

  override def toXML: Node = {
    scalaxb.toXML[cmrequest.v01p21.CMRequest](
      toDoc,
      schemaLocation,
      rootNodeLabel,
      toScope,
      typeAttribute = true).head
  }
}

case class CMRequestOfflineRegistrationXMLMessage(message: EbMsMessage) extends EdaXMLMessage[cmrequest.v01p20.CMRequest] {
  override implicit val edaTypeCanWrite: CanWriteXML[v01p20.CMRequest] = Cmrequestv01p20_CMRequestFormat

  override def rootNodeLabel: Option[String] = Some("CMRequest")

  override def schemaLocation: Option[String] =
    Some("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p20 " +
      "http://www.ebutilities.at/schemata/customerprocesses/EC_REQ_OFF/02.00/ANFORDERUNG_ECOF")

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
      typeAttribute = true).head
  }
}

case class CMRequestOfflineRegistrationXMLMessageV0220(message: EbMsMessage, processVersion: String = "02.20") extends EdaXMLMessage[cmrequest.v01p30.CMRequest] {
  override implicit val edaTypeCanWrite: CanWriteXML[v01p30.CMRequest] = Cmrequestv01p30_CMRequestFormat

  override def rootNodeLabel: Option[String] = Some("CMRequest")

  override def schemaLocation: Option[String] =
    Some("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p30 " +
      s"http://www.ebutilities.at/schemata/customerprocesses/EC_REQ_OFF/$processVersion/ANFORDERUNG_ECOF")

  override def toDoc: cmrequest.v01p30.CMRequest = CMRequestV0130Document(message).toDoc

  override def toScope: NamespaceBinding = scalaxb.toScope(
    None -> "http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p30",
    Some("ct") -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance"
  )

  override def toXML: Node = {
    scalaxb.toXML[cmrequest.v01p30.CMRequest](
      toDoc,
      schemaLocation,
      rootNodeLabel,
      toScope,
      typeAttribute = true).head
  }
}