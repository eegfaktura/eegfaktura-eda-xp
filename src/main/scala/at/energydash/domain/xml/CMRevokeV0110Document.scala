package at.energydash.domain.xml

import at.energydash.config.Config
import at.energydash.domain.eda.MessageHelper.{buildCalendar, buildCalendarDate}
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.domain.{EbMsMessage, ResponseData}
import cmrevoke.v01p10._
import commontypes.v01p20._
import ponton.`package`.{Cmrevokev01p10_SchemaVersionFormat, Commontypesv01p20_AddressTypeFormat, Commontypesv01p20_DocumentModeFormat, __BooleanXMLFormat}
import scalaxb.Helper

import java.util.Date

class CMRevokeV0110Document(doc: CMRevoke) {
  def toDoc: CMRevoke = doc

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

object CMRevokeV0110Document {

  def apply(doc: CMRevoke): CMRevokeV0110Document = new CMRevokeV0110Document(doc)

  def apply(message: EbMsMessage): CMRevokeV0110Document = new CMRevokeV0110Document(CMRevoke(
    MarketParticipantDirectory = cmrevoke.v01p10.MarketParticipantDirectory(
      RoutingHeader = RoutingHeader(
        Sender = RoutingAddress(message.sender, Map(("@AddressType", scalaxb.DataRecord[AddressType](ECNumber)))),
        Receiver = RoutingAddress(message.receiver, Map(("@AddressType", scalaxb.DataRecord[AddressType](ECNumber)))),
        DocumentCreationDateTime = Helper.toCalendar(buildCalendar(new Date))
      ),
      Sector = Number01,
      MessageCode = message.messageCode.toString,
      attributes = Map(
        ("@DocumentMode", scalaxb.DataRecord[DocumentMode](Config.interfaceMode match {
          case "SIMU" => SIMU
          case _ => PROD
        })),
        ("@Duplicate", scalaxb.DataRecord(false)),
        ("@SchemaVersion", scalaxb.DataRecord[SchemaVersion](Number01u4610)),
      )
    ),
    ProcessDirectory = cmrevoke.v01p10.ProcessDirectory(
      MessageId = message.messageId.get,
      ConversationId = message.conversationId,
      ConsentId = message.meter.flatMap(_.consentId).get,
      MeteringPoint = message.meter.map(x => x.meteringPoint).get,
      ConsentEnd = Helper.toCalendar(buildCalendarDate(message.consentEnd.getOrElse(new Date))),
      Reason = message.reason,
      ReasonKey = message.reasonKey
    )))
}
