package at.energydash.domain.xml

import at.energydash.config.Config
import at.energydash.domain.EbMsMessage
import at.energydash.domain.eda.MessageHelper.{buildCalendar, buildCalendarDate, getProcessDate, getNow}
import at.energydash.domain.enums.MeterDirectionType
import cmrequest._
import commontypes.v01p20._
import ponton.`package`.{Cmrequestv01p21_SchemaVersionFormat, Commontypesv01p20_AddressTypeFormat, Commontypesv01p20_DocumentModeFormat, __BooleanXMLFormat}
import scalaxb.Helper

import java.util.{Date, GregorianCalendar}

class CMRequestV0121Document(doc: v01p21.CMRequest) {
  def toDoc: v01p21.CMRequest = doc
}

object CMRequestV0121Document {
  def apply(message: EbMsMessage):CMRequestV0121Document = new CMRequestV0121Document(v01p21.CMRequest(
    MarketParticipantDirectory = v01p21.MarketParticipantDirectory(
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
        ("@SchemaVersion", scalaxb.DataRecord[v01p21.SchemaVersion](v01p21.Number01u4621)),
      )
    ),
    ProcessDirectory = v01p21.ProcessDirectory(
      MessageId = message.messageId.get,
      ConversationId = message.conversationId,
      ProcessDate = Helper.toCalendar(getProcessDate),
      MeteringPoint = message.meter.map(x => x.meteringPoint),
      CMRequestId = message.requestId.get,
      ConsentId = message.meter.flatMap(m=>m.consentId),
      CMRequest = Some(v01p21.ReqType(
        ReqDatType = "EnergyCommunityRegistration",
        DateFrom = Helper.toCalendar(
          message.meter.flatMap(m => m.from.map (f => buildCalendarDate(f)))
            .getOrElse(getNow(Some(1)).toString)),
        DateTo = Some(Helper.toCalendar(buildCalendarDate(new GregorianCalendar(2099, 12, 31).getTime))),
        ECPartFact=message.meter.map { m => m.partFact.getOrElse(100)},
        MeteringIntervall = None, //Some(QHValue),
        TransmissionCycle = None, //Some(DValue2),
        ECID = message.ecId,
        ECShare = message.meter.flatMap(_.share),
        EnergyDirection = message.meter.map { m =>
          m.direction match {
            case Some(MeterDirectionType.CONSUMPTION) => v01p21.CONSUMPTION
            case Some(MeterDirectionType.GENERATION) => v01p21.GENERATION
            case _ => v01p21.CONSUMPTION
          }
        }
      ))
    )
  ))
}
