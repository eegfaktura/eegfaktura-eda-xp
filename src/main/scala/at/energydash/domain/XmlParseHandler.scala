package at.energydash.domain

import at.energydash.domain.enums.EbMsMessageType
import at.energydash.domain.xml._
import ecmplist.v01p10
import ecmplist.v01p00
import org.slf4j.LoggerFactory
import ponton.InboundDocument
import scalaxb.DataRecord
import soapenvelope11.Envelope

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

object XmlParseHandler {
  import ponton.`package`.fromAnySchemaType

  val logger = LoggerFactory.getLogger("XmlParseHandler")

  private[this] val SOAP_ENVELOPE_URI =
    "http://www.w3.org/2003/05/soap-envelope"

  case class ParseHeader(SenderId: String, ReceiverId: String, ConversationId: Option[String] = None, MessageType: Option[String] = None)

  def responseBodyXml(envelope: Envelope)(implicit ec: ExecutionContext): Future[NodeSeq] = {
    //    val envelope = scalaxb.fromXML[Envelope](responseXml)

    Future {
      envelope.Body.any.headOption match {
        case Some(DataRecord(_, _, x: scala.xml.Elem))
          if (x.label == "Fault") &&
            (x.scope.getURI(x.prefix) == SOAP_ENVELOPE_URI) =>
          // TODO: much advance failure handling
          // val fault = scalaxb.fromXML[soapenvelope12.Fault](x)
          logger.error(s"Error in Body Message: ${x}")
          Nil
        case _ =>
          envelope.Body.any.collect {
            case DataRecord(_, _, x: scala.xml.Node) => x
          }
      }
    }
  }

  def responseInbound(envelope: Envelope)(implicit ec: ExecutionContext) : Future[InboundDocument] = {
    responseBodyXml(envelope).map(n => scalaxb.fromXML[InboundDocument](n.head))
  }

  def reponseEbMsMessage(envelope: Envelope)(implicit ec: ExecutionContext) : Future[EbMsMessage] = {
    responseInbound(envelope).map(x => mapDataRecordToEbms(
      ParseHeader(x.Header.SenderId, x.Header.ReceiverId, Some(x.Header.ConversationId), Some(x.Header.MessageType)),
      x.Message.messageoption))
  }

  def mapXmlToEbms(header: ParseHeader, xml: scala.xml.Elem): EbMsMessage = {
    mapDataRecordToEbms(header, scalaxb.DataRecord(scalaxb.ElemName(xml)))
  }

  def mapDataRecordToEbms(header: ParseHeader, dr: scalaxb.DataRecord[Any]): EbMsMessage = {
    dr match {
      case DataRecord(_, _, x: v01p10.ECMPList) => ECMPListV0110Document(x).toMessage
      case DataRecord(_, _, x: v01p00.ECMPList) => ECMPListV0100Document(x).toMessage
      //      case DataRecord(_, _, x: v01p00.ECMPList) => ECMPListV0110Document(x).toMessage
      case DataRecord(_, _, x: cmnotification.v01p11.CMNotification) => CMNotificationV0111Document(x).toMessage
      case DataRecord(_, _, x: cmnotification.v01p12.CMNotification) => CMNotificationV0112Document(x).toMessage
      case DataRecord(_, _, x: cmrevoke.v01p00.CMRevoke) => CMRevokeV0100Document(x).toMessage
      case DataRecord(_, _, x: cpnotification.v01p13.CPNotification) => CPNotificationV0113Document(x).toMessage
      case DataRecord(_, _, x: consumptionrecord.v01p30.ConsumptionRecord) => ConsumptionRecordV0130(x).toMessage
      case DataRecord(_, _, x: consumptionrecord.v01p40.ConsumptionRecord) => ConsumptionRecordV0140(x).toMessage
      case _ => EbMsMessage(
        conversationId = header.ConversationId.getOrElse("MISSING"),
        sender = header.SenderId,
        receiver = header.ReceiverId,
        messageCode = EbMsMessageType.ERROR_MESSAGE,
        errorMessage = Some(s"Unknown or not registered MessageType ${header.MessageType.getOrElse("MISSING")}")
      )
    }
  }
}
