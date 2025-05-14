package at.energydash.domain.xml

import at.energydash.config.Config
import at.energydash.domain.eda.MessageHelper.{buildCalendar, buildCalendarDate, getProcessDate}
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.domain.{EbMsMessage, ResponseData}
import cmrevoke._
//import cmrevoke.v01p00.MessageCode
import commontypes.v01p20._
import ponton.`package`.{Cmrevokev01p00_MessageCodeFormat, Cmrevokev01p00_SchemaVersionFormat, Commontypesv01p20_AddressTypeFormat, Commontypesv01p20_DocumentModeFormat, __BooleanXMLFormat}
import scalaxb.Helper

import java.util.Date
import scala.xml.TopScope

class CMRevokeV0100Document(doc: v01p00.CMRevoke) {
  def toDoc: v01p00.CMRevoke = doc

  def toMessage: EbMsMessage = EbMsMessage(
    messageId = Some(doc.ProcessDirectory.MessageId),
    conversationId = doc.ProcessDirectory.ConversationId,
    sender = doc.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
    receiver = doc.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
    messageCode = EbMsMessageType.withName(doc.MarketParticipantDirectory.MessageCode.toString),
    messageCodeVersion = Some("01.00"),
    responseData = Some(List(ResponseData(
      MeteringPoint = Some(doc.ProcessDirectory.MeteringPoint),
      ResponseCode = List(1099),
      ConsentEnd = Some(doc.ProcessDirectory.ConsentEnd.toGregorianCalendar().getTime.getTime),
      ConsentId = Some(doc.ProcessDirectory.ConsentId)
    )))
  )
}

object CMRevokeV0100Document {

  def apply(doc: v01p00.CMRevoke): CMRevokeV0100Document = new CMRevokeV0100Document(doc)

  def apply(message: EbMsMessage): CMRevokeV0100Document = new CMRevokeV0100Document(v01p00.CMRevoke(
    MarketParticipantDirectory = v01p00.MarketParticipantDirectory(
      RoutingHeader = RoutingHeader(
        Sender = RoutingAddress(message.sender, Map(("@AddressType", scalaxb.DataRecord[AddressType](ECNumber)))),
        Receiver = RoutingAddress(message.receiver, Map(("@AddressType", scalaxb.DataRecord[AddressType](ECNumber)))),
        DocumentCreationDateTime = Helper.toCalendar(buildCalendar(getProcessDate.getTime))
      ),
      Sector = Number01,
      MessageCode = v01p00.MessageCode.fromString(message.messageCode.toString, TopScope),
      attributes = Map(
        ("@DocumentMode", scalaxb.DataRecord[DocumentMode](Config.interfaceMode match {
          case "SIMU" => SIMU
          case _ => PROD
        })),
        ("@Duplicate", scalaxb.DataRecord(false)),
        ("@SchemaVersion", scalaxb.DataRecord[v01p00.SchemaVersion](v01p00.Number01u4600)),
      )
    ),
    ProcessDirectory = v01p00.ProcessDirectory(
      MessageId = message.messageId.get,
      ConversationId = message.conversationId,
      ConsentId = message.meter.flatMap(_.consentId).get,
      MeteringPoint = message.meter.map(x => x.meteringPoint).get,
      ConsentEnd = Helper.toCalendar(buildCalendarDate(message.consentEnd.getOrElse(new Date))),
      Reason = message.reason,
    )))
}
