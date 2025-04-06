package at.energydash.domain.xml

import at.energydash.config.Config
import at.energydash.domain.EbMsMessage
import at.energydash.domain.eda.MessageHelper.{buildCalendar, buildCalendarDate, getProcessDate}
import at.energydash.domain.enums.MeterDirectionType
import cmrequest._
import commontypes.v01p20._
import ponton.`package`.{Cmrequestv01p20_SchemaVersionFormat, Commontypesv01p20_AddressTypeFormat, Commontypesv01p20_DocumentModeFormat, __BooleanXMLFormat}
import scalaxb.Helper

import java.util.{Date, GregorianCalendar}

class CMRequestV0120Document(doc: v01p20.CMRequest) {
  def toDoc: v01p20.CMRequest = doc
}

object CMRequestV0120Document {
  def apply(message: EbMsMessage):CMRequestV0120Document = new CMRequestV0120Document(v01p20.CMRequest(
    MarketParticipantDirectory = v01p20.MarketParticipantDirectory(
      RoutingHeader(
        RoutingAddress(message.sender, Map(("@AddressType", scalaxb.DataRecord[AddressType](ECNumber)))),
        RoutingAddress(message.receiver, Map(("@AddressType", scalaxb.DataRecord[AddressType](ECNumber)))),
        Helper.toCalendar(buildCalendar(new Date))
      ),
      Number01,
      message.messageCode.toString,
      Map(
        ("@DocumentMode", scalaxb.DataRecord[DocumentMode](Config.interfaceMode match {
          case "SIMU" => SIMU
          case _ => PROD
        })),
        ("@Duplicate", scalaxb.DataRecord(false)),
        ("@SchemaVersion", scalaxb.DataRecord[v01p20.SchemaVersion](v01p20.Number01u4620)),
      )
    ),
    ProcessDirectory = v01p20.ProcessDirectory(
      MessageId = message.messageId.get,
      ConversationId = message.conversationId,
      ProcessDate = Helper.toCalendar(buildCalendarDate(getProcessDate.getTime)),
      MeteringPoint = message.meter.map(x => x.meteringPoint),
      CMRequestId = message.requestId.get,
      ConsentId = message.meter.flatMap(m=>m.consentId),
      CMRequest = v01p20.ReqType(
        ReqDatType = "EnergyCommunityRegistration",
        DateFrom=Helper.toCalendar(
          message.meter.flatMap(m => m.from.map (f => buildCalendarDate(f)))
            .getOrElse(buildCalendarDate(getProcessDate.getTime))),
        DateTo = Some(Helper.toCalendar(buildCalendarDate(new GregorianCalendar(2099, 12, 31).getTime))),
        ECPartFact=message.meter.map { m => m.partFact.getOrElse(100)},
        MeteringIntervall = None, //Some(QHValue),
        TransmissionCycle = None, //Some(DValue2),
        ECID = message.ecId,
        ECShare = message.meter.flatMap(_.share),
        EnergyDirection = message.meter.map { m =>
          m.direction match {
            case Some(MeterDirectionType.CONSUMPTION) => v01p20.CONSUMPTION
            case Some(MeterDirectionType.GENERATION) => v01p20.GENERATION
            case _ => v01p20.CONSUMPTION
          }
        }
      )
    )
  ))
}
