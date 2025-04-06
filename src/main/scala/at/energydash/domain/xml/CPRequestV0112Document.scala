package at.energydash.domain.xml

import at.energydash.config.Config
import at.energydash.domain.EbMsMessage
import at.energydash.domain.eda.MessageHelper
import at.energydash.domain.eda.MessageHelper.buildCalendar
import cprequest.v01p12.{CPRequest, Extension}
import ponton.`package`.{Commontypesv01p20_AddressTypeFormat, Commontypesv01p20_DocumentModeFormat, Cprequestv01p12_SchemaVersionFormat, __BooleanXMLFormat}
import scalaxb.Helper

import java.util.Date

case class CPRequestV0112Document(doc: CPRequest, message: EbMsMessage) {

  def withExtention(extension: Option[Extension]) =
    copy(doc =
      doc.copy(ProcessDirectory =
        doc.ProcessDirectory.copy(Extension = extension)))

  def toDoc(): CPRequest = doc
}


object CPRequestV0112Document {

  def apply(message: EbMsMessage): CPRequestV0112Document = new CPRequestV0112Document(
    doc = CPRequest(
      cprequest.v01p12.MarketParticipantDirectory(
        commontypes.v01p20.RoutingHeader(
          commontypes.v01p20.RoutingAddress(message.sender, Map(("@AddressType", scalaxb.DataRecord[commontypes.v01p20.AddressType](commontypes.v01p20.ECNumber)))),
          commontypes.v01p20.RoutingAddress(message.receiver, Map(("@AddressType", scalaxb.DataRecord[commontypes.v01p20.AddressType](commontypes.v01p20.ECNumber)))),
          Helper.toCalendar(buildCalendar(new Date))
        ),
        commontypes.v01p20.Number01,
        message.messageCode.toString,
        Map(
          ("@DocumentMode", scalaxb.DataRecord[commontypes.v01p20.DocumentMode](Config.interfaceMode match {
            case "SIMU" => commontypes.v01p20.SIMU
            case _ => commontypes.v01p20.PROD
          })),
          ("@Duplicate", scalaxb.DataRecord(false)),
          ("@SchemaVersion", scalaxb.DataRecord[cprequest.v01p12.SchemaVersion](cprequest.v01p12.Number01u4612)),
        )
      ),
      cprequest.v01p12.ProcessDirectory(
        MessageId = message.messageId.get,
        ConversationId = message.conversationId,
        ProcessDate = Helper.toCalendar(MessageHelper.buildCalendarDate(MessageHelper.getProcessDate.getTime)),
        MeteringPoint = message.meter.map(x=>x.meteringPoint).getOrElse(""),
      )
    ),
    message
  )
}
