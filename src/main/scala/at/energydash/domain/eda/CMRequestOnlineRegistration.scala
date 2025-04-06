package at.energydash.domain.eda

import at.energydash.domain.EbMsMessage
import at.energydash.domain.xml.{CMRequestV0110Document, CMRequestV0120Document,  CMRequestV0121Document}
import ponton.`package`._
import scalaxb.CanWriteXML

import scala.xml.{NamespaceBinding, Node}


case class CMRequestRegistrationOnline(message: EbMsMessage) extends EdaMessage {
  override def getVersion(version: Option[String] = None): EdaXMLMessage[_] = message.messageCodeVersion match {
    case Some("02.00") => CMRequestRegistrationOnlineXMLMessageV0200(message)
    case Some("02.10") => CMRequestRegistrationOnlineXMLMessageV0210(message)
    case _ => CMRequestRegistrationOnlineXMLMessageV0110(message)
  }
}


case class CMRequestRegistrationOnlineXMLMessageV0210(message: EbMsMessage) extends EdaXMLMessage[cmrequest.v01p21.CMRequest] {
  override implicit val edaTypeCanWrite: CanWriteXML[cmrequest.v01p21.CMRequest] = Cmrequestv01p21_CMRequestFormat

  override def rootNodeLabel: Option[String] = Some("ns2:CMRequest")

  override def schemaLocation: Option[String] =
    Some("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p21 http://www.ebutilities.at/schemata/customerprocesses/EC_REQ_ONL/02.10/ANFORDERUNG_ECON")

  override def toDoc: cmrequest.v01p21.CMRequest = CMRequestV0121Document(message).toDoc

  override def toScope: NamespaceBinding = scalaxb.toScope(
    //    Some("ns2") -> "http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p20",
    None -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("ct") -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance"
  )

  override def toXML: Node = {
    scalaxb.toXML[cmrequest.v01p21.CMRequest](toDoc, schemaLocation, rootNodeLabel,
      toScope,
      typeAttribute = true).head
  }
}

case class CMRequestRegistrationOnlineXMLMessageV0200(message: EbMsMessage) extends EdaXMLMessage[cmrequest.v01p20.CMRequest] {
  override implicit val edaTypeCanWrite: CanWriteXML[cmrequest.v01p20.CMRequest] = Cmrequestv01p20_CMRequestFormat

  override def rootNodeLabel: Option[String] = Some("ns2:CMRequest")

  override def schemaLocation: Option[String] =
    Some("http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p20 http://www.ebutilities.at/schemata/customerprocesses/EC_REQ_ONL/02.00/ANFORDERUNG_ECON")

  override def toDoc: cmrequest.v01p20.CMRequest = CMRequestV0120Document(message).toDoc

  override def toScope: NamespaceBinding = scalaxb.toScope(
//    Some("ns2") -> "http://www.ebutilities.at/schemata/customerconsent/cmrequest/01p20",
    None -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("ct") -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance"
  )

  override def toXML: Node = {
    scalaxb.toXML[cmrequest.v01p20.CMRequest](toDoc, schemaLocation, rootNodeLabel,
      toScope,
      typeAttribute = true).head
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
      typeAttribute = true).head
  }
}