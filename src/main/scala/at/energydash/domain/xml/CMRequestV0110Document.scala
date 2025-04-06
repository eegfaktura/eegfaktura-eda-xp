package at.energydash.domain.xml

import at.energydash.config.Config
import at.energydash.domain.EbMsMessage
import at.energydash.domain.eda.MessageHelper
import at.energydash.domain.eda.MessageHelper.{buildCalendarDate, getProcessDate}
import at.energydash.domain.enums.MeterDirectionType
import cmrequest._
import commontypes.v01p20._
import ponton.`package`.{Cmrequestv01p10_SchemaVersionFormat, Commontypesv01p20_AddressTypeFormat, Commontypesv01p20_DocumentModeFormat, __BooleanXMLFormat}
import scalaxb.Helper

import java.util.{Date, GregorianCalendar}

class CMRequestV0110Document(doc: v01p10.CMRequest) {
  def toDoc: v01p10.CMRequest = doc
}

object CMRequestV0110Document {
//  val processCalendar: GregorianCalendar = MessageHelper.getProcessDate

  def apply(message: EbMsMessage):CMRequestV0110Document = new CMRequestV0110Document(v01p10.CMRequest(
    MarketParticipantDirectory = v01p10.MarketParticipantDirectory(
      RoutingHeader(
        RoutingAddress(message.sender, Map(("@AddressType", scalaxb.DataRecord[AddressType](ECNumber)))),
        RoutingAddress(message.receiver, Map(("@AddressType", scalaxb.DataRecord[AddressType](ECNumber)))),
        Helper.toCalendar(MessageHelper.buildCalendar(new Date))
      ),
      Number01,
      message.messageCode.toString,
      Map(
        ("@DocumentMode", scalaxb.DataRecord[DocumentMode](Config.interfaceMode match {
          case "SIMU" => SIMU
          case _ => PROD
        })),
        ("@Duplicate", scalaxb.DataRecord(false)),
        ("@SchemaVersion", scalaxb.DataRecord[v01p10.SchemaVersion](v01p10.Number01u4610)),
      )

    ),
    ProcessDirectory = v01p10.ProcessDirectory(
      MessageId = message.messageId.get,
      ConversationId = message.conversationId,
      ProcessDate = Helper.toCalendar(buildCalendarDate(getProcessDate.getTime)),
      MeteringPoint = message.meter.map(x => x.meteringPoint),
      CMRequestId = message.requestId.get,
      ConsentId = message.meter.map(m => m.consentId.getOrElse("")),
      CMRequest = v01p10.ReqType(
        ReqDatType = "EnergyCommunityRegistration",
        DateFrom = Helper.toCalendar(
          message.meter.flatMap(m => m.from.map (f => buildCalendarDate(f)))
            .getOrElse(buildCalendarDate(getProcessDate.getTime))),
        DateTo = Some(Helper.toCalendar(buildCalendarDate(new GregorianCalendar(2099, 12, 31).getTime))),
        MeteringIntervall = None, //Some(QHValue),
        TransmissionCycle = None, //Some(DValue2),
        ECID = message.ecId,
        ECShare = message.meter.flatMap(_.share),
        EnergyDirection = message.meter.map { m =>
          m.direction match {
            case Some(MeterDirectionType.CONSUMPTION) => v01p10.CONSUMPTION
            case Some(MeterDirectionType.GENERATION) => v01p10.GENERATION
            case _ => v01p10.CONSUMPTION
          }
        }
      )
    )
  ))
}
