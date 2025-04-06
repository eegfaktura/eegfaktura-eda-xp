package at.energydash.domain.xml

import at.energydash.config.Config
import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.MeterDirectionType
import cmrequest._
import commontypes.v01p20._
import ponton.`package`.{Cmrequestv01p21_SchemaVersionFormat, Commontypesv01p20_AddressTypeFormat, Commontypesv01p20_DocumentModeFormat, __BooleanXMLFormat}
import scalaxb.Helper

import java.text.SimpleDateFormat
import java.util.{Calendar, Date, GregorianCalendar}

class CMRequestV0121Document(doc: v01p21.CMRequest) {
  def toDoc: v01p21.CMRequest = doc
}

object CMRequestV0121Document {
  val calendar: GregorianCalendar = new GregorianCalendar
  calendar.setTime(new Date)
  calendar.set(Calendar.MILLISECOND, 0)

  val processCalendar = new GregorianCalendar(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
  processCalendar.add(Calendar.DAY_OF_MONTH, 3)
  val dateFmt = new SimpleDateFormat("yyyy-MM-dd")

  def apply(message: EbMsMessage):CMRequestV0121Document = new CMRequestV0121Document(v01p21.CMRequest(
    MarketParticipantDirectory = v01p21.MarketParticipantDirectory(
      RoutingHeader(
        RoutingAddress(message.sender, Map(("@AddressType", scalaxb.DataRecord[AddressType](ECNumber)))),
        RoutingAddress(message.receiver, Map(("@AddressType", scalaxb.DataRecord[AddressType](ECNumber)))),
        Helper.toCalendar(calendar)
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
      ProcessDate = Helper.toCalendar(dateFmt.format(calendar.getTime)),
      MeteringPoint = message.meter.map(x => x.meteringPoint),
      CMRequestId = message.requestId.get,
      ConsentId = message.meter.flatMap(m=>m.consentId),
      CMRequest = Some(v01p21.ReqType(
        ReqDatType = "EnergyCommunityRegistration",
        DateFrom = Helper.toCalendar(dateFmt.format(processCalendar.getTime)),
        DateTo = Some(Helper.toCalendar(dateFmt.format(new GregorianCalendar(2099, 12, 31).getTime))),
        ECPartFact=message.meter.map { m => m.partFact.getOrElse(100)},
        MeteringIntervall = None, //Some(QHValue),
        TransmissionCycle = None, //Some(DValue2),
        ECID = message.ecId,
        ECShare = None, //Some(BigDecimal(0.0)),
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
