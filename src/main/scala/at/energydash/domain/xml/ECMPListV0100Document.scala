package at.energydash.domain.xml

import at.energydash.config.Config
import at.energydash.domain.eda.MessageHelper
import at.energydash.domain.enums.{EbMsMessageType, EcDisModelEnum, EcTypeEnum, MeterDirectionType}
import at.energydash.domain.{EbMsMessage, Meter}
import ecmplist.v01p00.ECMPList
import ponton.`package`.{Commontypesv01p20_AddressTypeFormat, Commontypesv01p20_DocumentModeFormat, Ecmplistv01p10_SchemaVersionFormat, __BooleanXMLFormat}
import scalaxb.Helper

import java.util.{Calendar, Date, GregorianCalendar, Locale}

case class ECMPListV0100Document(doc: ecmplist.v01p00.ECMPList) {
  private def calcDateFrom(): Date = {
    val now = new GregorianCalendar(new Locale("de", "AT"))
    now.add(Calendar.DAY_OF_MONTH, 1)
    if (now.get(Calendar.HOUR_OF_DAY) > 16) {
      now.add(Calendar.DAY_OF_MONTH, 1)
    }
    now.getTime
  }

  def toDoc: ECMPList = doc

  def withMeterList(mList: Option[Seq[Meter]]): ECMPListV0100Document =
    copy(doc=doc.copy(ProcessDirectory =
      doc.ProcessDirectory.copy(MPListData = mList match {
        case Some(ml) => ml.map(m=>ecmplist.v01p00.MPListData(
          MeteringPoint = m.meteringPoint,
          MPTimeData = Seq(ecmplist.v01p00.MPTimeData(
            DateFrom = Helper.toCalendar(MessageHelper.buildCalendarDate(calcDateFrom())),
            DateTo = Helper.toCalendar("2099-12-31"),
            EnergyDirection = m.direction match {
              case Some(MeterDirectionType.CONSUMPTION) => ecmplist.v01p00.CONSUMPTION
              case _ => ecmplist.v01p00.GENERATION
            },
            DateActivate = Helper.toCalendar(MessageHelper.buildCalendarDate(m.activation.get)),
//            DateDeactivate = Helper.toCalendar(MessageHelper.buildCalendarDate(m.activation.get))
          ))
        ))
        case None => Nil
      })))

  def toMessage: EbMsMessage = {
    EbMsMessage(
      messageId = Some(doc.ProcessDirectory.MessageId),
      conversationId = doc.ProcessDirectory.ConversationId,
      sender = doc.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
      receiver = doc.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
      messageCode = EbMsMessageType.withName(doc.MarketParticipantDirectory.MessageCode.toString),
      messageCodeVersion = Some("01.00"),
      ecId = Some(doc.ProcessDirectory.ECID),
      meterList = Some(doc.ProcessDirectory.MPListData
        .flatMap(m =>
          m.MPTimeData.map(mp =>
            Meter(
              meteringPoint = m.MeteringPoint,
              direction = Some(MeterDirectionType.withName(mp.EnergyDirection.toString)),
              activation = Some(mp.DateActivate.toGregorianCalendar.getTime),
              from = Some(mp.DateFrom.toGregorianCalendar.getTime),
              to = Some(mp.DateTo.toGregorianCalendar.getTime),
              share = mp.ECShare,
              plantCategory = mp.PlantCategory,
            ))
        )
      )
    )
  }
}

object ECMPListV0100Document {
  val now = new Date

  def apply(doc: ecmplist.v01p00.ECMPList) = new ECMPListV0100Document(doc)

  def apply(message: EbMsMessage): ECMPListV0100Document = new ECMPListV0100Document(
    doc = ecmplist.v01p00.ECMPList(
      MarketParticipantDirectory=ecmplist.v01p00.MarketParticipantDirectory(
      RoutingHeader=commontypes.v01p20.RoutingHeader(
        commontypes.v01p20.RoutingAddress(message.sender, Map(("@AddressType", scalaxb.DataRecord[commontypes.v01p20.AddressType](commontypes.v01p20.ECNumber)))),
        commontypes.v01p20.RoutingAddress(message.receiver, Map(("@AddressType", scalaxb.DataRecord[commontypes.v01p20.AddressType](commontypes.v01p20.ECNumber)))),
        Helper.toCalendar(MessageHelper.buildCalendar(now))
      ),
      Sector=commontypes.v01p20.Number01,
      MessageCode=ecmplist.v01p00.ANFORDERUNG_ECC,
      attributes=Map(
        ("@DocumentMode", scalaxb.DataRecord[commontypes.v01p20.DocumentMode](Config.interfaceMode match {
          case "SIMU" => commontypes.v01p20.SIMU
          case _ => commontypes.v01p20.PROD
        })),
        ("@Duplicate", scalaxb.DataRecord(false)),
        ("@SchemaVersion", scalaxb.DataRecord[ecmplist.v01p10.SchemaVersion](ecmplist.v01p10.Number01u4610)),
      )
    ),
      ProcessDirectory=ecmplist.v01p00.ProcessDirectory(
        MessageId = message.messageId.get,
        ConversationId = message.conversationId,
        ProcessDate = Helper.toCalendar(MessageHelper.getProcessDate),
        ECID = message.ecId.get,
        ECType = message.ecType match {
          case Some(EcTypeEnum.GEA) => ecmplist.v01p00.GC
          case Some(EcTypeEnum.REGIONAL) => ecmplist.v01p00.RC_R
          case Some(EcTypeEnum.BEG) => ecmplist.v01p00.CC
          case _ => ecmplist.v01p00.RC_L
        },
        ECDisModel = message.ecDisModel match {
          case Some(EcDisModelEnum.STATIC) => ecmplist.v01p00.S
          case _ => ecmplist.v01p00.D
        },
      )
    )
  )
}
