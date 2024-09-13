package at.energydash.domain.xml

import at.energydash.config.Config
import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.MeterDirectionType
import commontypes.v01p20._
import cmrequest._
import ponton.`package`.{Cmrequestv01p20_SchemaVersionFormat, Commontypesv01p20_AddressTypeFormat, Commontypesv01p20_DocumentModeFormat, __BooleanXMLFormat}
import scalaxb.Helper

import java.text.SimpleDateFormat
import java.util.{Calendar, Date, GregorianCalendar}

class CMRequestV0120Document(doc: v01p20.CMRequest) {
  def toDoc: v01p20.CMRequest = doc
}

object CMRequestV0120Document {
  val calendar: GregorianCalendar = new GregorianCalendar
  calendar.setTime(new Date)
  calendar.set(Calendar.MILLISECOND, 0)

  val processCalendar = new GregorianCalendar(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
  processCalendar.add(Calendar.DAY_OF_MONTH, 3)
  val dateFmt = new SimpleDateFormat("yyyy-MM-dd")

  def apply(message: EbMsMessage):CMRequestV0120Document = new CMRequestV0120Document(v01p20.CMRequest(
    MarketParticipantDirectory = v01p20.MarketParticipantDirectory(
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
        ("@SchemaVersion", scalaxb.DataRecord[v01p20.SchemaVersion](v01p20.Number01u4620)),
      )
    ),
    ProcessDirectory = v01p20.ProcessDirectory(
      MessageId = message.messageId.get,
      ConversationId = message.conversationId,
      ProcessDate = Helper.toCalendar(dateFmt.format(calendar.getTime)),
      MeteringPoint = message.meter.map(x => x.meteringPoint),
      CMRequestId = message.requestId.get,
      ConsentId = message.meter.flatMap(m=>m.consentId),
      CMRequest = v01p20.ReqType(
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
            case Some(MeterDirectionType.CONSUMPTION) => v01p20.CONSUMPTION
            case Some(MeterDirectionType.GENERATION) => v01p20.GENERATION
            case _ => v01p20.CONSUMPTION
          }
        }
      )
    )
  ))
}
