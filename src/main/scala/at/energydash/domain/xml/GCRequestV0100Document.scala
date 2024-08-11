package at.energydash.domain.xml

import at.energydash.config.Config
import at.energydash.domain.EbMsMessage
import at.energydash.domain.eda.MessageHelper
import at.energydash.domain.eda.MessageHelper.getProcessDate
import at.energydash.domain.xml.ECMPListV0110Document.now
import gcrequest.v01p00.{GCRequest, MarketParticipantDirectory, MessageCode, ProcessDirectory}
import ponton.`package`.{Commontypesv01p20_AddressTypeFormat, Commontypesv01p20_DocumentModeFormat, Gcrequestv01p00_MessageCodeFormat, Gcrequestv01p00_SchemaVersionFormat, __BooleanXMLFormat}
import scalaxb.Helper

import scala.xml.TopScope


class GCRequestV0100Document(doc: GCRequest) {
  def toDoc(): GCRequest = doc
}

object GCRequestV0100Document {
  def apply(message: EbMsMessage):GCRequestV0100Document = new GCRequestV0100Document(
    doc = GCRequest(
      MarketParticipantDirectory = MarketParticipantDirectory(
        RoutingHeader = commontypes.v01p20.RoutingHeader(
          commontypes.v01p20.RoutingAddress(message.sender, Map(("@AddressType", scalaxb.DataRecord[commontypes.v01p20.AddressType](commontypes.v01p20.ECNumber)))),
          commontypes.v01p20.RoutingAddress(message.receiver, Map(("@AddressType", scalaxb.DataRecord[commontypes.v01p20.AddressType](commontypes.v01p20.ECNumber)))),
          Helper.toCalendar(MessageHelper.buildCalendar(now))
        ),
        Sector = commontypes.v01p20.Number01,
        MessageCode = MessageCode.fromString(message.messageCode.toString, TopScope),
        attributes = Map(
          ("@DocumentMode", scalaxb.DataRecord[commontypes.v01p20.DocumentMode](Config.interfaceMode match {
            case "SIMU" => commontypes.v01p20.SIMU
            case _ => commontypes.v01p20.PROD
          })),
          ("@Duplicate", scalaxb.DataRecord(false)),
          ("@SchemaVersion", scalaxb.DataRecord[gcrequest.v01p00.SchemaVersion](gcrequest.v01p00.Number01u4600)),
        )
      ),
      ProcessDirectory = ProcessDirectory(
        MessageId = message.messageId.get,
        ConversationId = message.conversationId,
        ProcessDate = Helper.toCalendar(MessageHelper.buildCalendarDate(getProcessDate.getTime)),
        MeteringPoint = ???,
        ContractPartner = ???,
        Extension = ???
      )
    ))
}