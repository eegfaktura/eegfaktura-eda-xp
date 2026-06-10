package at.energydash.domain.xml

import at.energydash.config.Config
import at.energydash.domain.EbMsMessage
import at.energydash.domain.eda.MessageHelper.{buildCalendar, buildCalendarDate, getProcessDate, getNow}
import at.energydash.domain.enums.MeterDirectionType
import cmrequest.v01p30
import commontypes.v01p20._
import ponton.`package`.{Cmrequestv01p30_SchemaVersionFormat, Commontypesv01p20_AddressTypeFormat, Commontypesv01p20_DocumentModeFormat, __BooleanXMLFormat}
import scalaxb.Helper

import java.util.{Date, GregorianCalendar}

class CMRequestV0130Document(doc: v01p30.CMRequest) {
  def toDoc: v01p30.CMRequest = doc
}

object CMRequestV0130Document {
  def apply(message: EbMsMessage):CMRequestV0130Document = new CMRequestV0130Document(v01p30.CMRequest(
    MarketParticipantDirectory = v01p30.MarketParticipantDirectory(
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
        ("@SchemaVersion", scalaxb.DataRecord[v01p30.SchemaVersion](v01p30.Number01u4630)),
      )
    ),
    ProcessDirectory = v01p30.ProcessDirectory(
      MessageId = message.messageId.get,
      ConversationId = message.conversationId,
      ProcessDate = Helper.toCalendar(getProcessDate),
      MeteringPoint = message.meter.map(x => x.meteringPoint),
      CMRequestId = message.requestId.get,
      ConsentId = message.meter.flatMap(m=>m.consentId),
      CMRequest = Some(v01p30.ReqType(
        ReqDatType = "EnergyCommunityRegistration",
        DateFrom = Helper.toCalendar(
          message.meter.flatMap(m => m.from.map (f => buildCalendarDate(f)))
            .getOrElse(getNow(Some(1)).toString)),
        DateTo = Some(Helper.toCalendar(buildCalendarDate(new GregorianCalendar(2099, 12, 31).getTime))),
        ECPartFact=message.meter.map { m => m.partFact.getOrElse(100)},
        MeteringIntervall = Some(v01p30.QH),
        TransmissionCycle = None, //Some(v01p30.D),
        ECID = message.ecId,
        ECShare = message.meter.flatMap(_.share),
        EnergyDirection = message.meter.map { m =>
          m.direction match {
            case Some(MeterDirectionType.CONSUMPTION) => v01p30.CONSUMPTION
            case Some(MeterDirectionType.GENERATION) => v01p30.GENERATION
            case _ => v01p30.CONSUMPTION
          }
        }
      ))
    )
  ))
}
