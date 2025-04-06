package at.energydash.domain.xml

import at.energydash.config.Config
import at.energydash.domain.eda.MessageHelper
import at.energydash.domain.eda.MessageHelper.{buildCalendar, buildCalendarDate, getProcessDate, getProcessDate1}
import at.energydash.domain.enums.{EbMsMessageType, EcDisModelEnum, EcTypeEnum, MeterDirectionType}
import at.energydash.domain.{EbMsMessage, Meter}
import ecmplist.v01p10.ECMPList
import ponton.`package`.{Commontypesv01p20_AddressTypeFormat, Commontypesv01p20_DocumentModeFormat, Ecmplistv01p10_SchemaVersionFormat, __BooleanXMLFormat}
import scalaxb.Helper

import java.util.{Calendar, Date, GregorianCalendar, Locale}

case class ECMPListV0110Document(doc: ecmplist.v01p10.ECMPList) {
  private def calcRestrictedDate(dt: Option[Date]): Option[Date] = dt.map(buildCalendar).map(d => {
      val now = new GregorianCalendar(new Locale("de", "AT"))
      if (now.get(Calendar.HOUR_OF_DAY) > 16 || now.get(Calendar.HOUR_OF_DAY) < 9) {
        d.add(Calendar.DAY_OF_MONTH, 1)
      }
      d
    }).map(_.getTime)

  def toDoc: ECMPList = doc

  def withRestrictedProcessDate(): ECMPListV0110Document = {
    copy(doc=doc.copy(
      ProcessDirectory = doc.ProcessDirectory.copy(
        ProcessDate = Helper.toCalendar(getProcessDate1(Some(1)).toString),
        MPListData = doc.ProcessDirectory.MPListData.map(mpdata => ecmplist.v01p10.MPListData(
          MeteringPoint = mpdata.MeteringPoint,
          ConsentId = mpdata.ConsentId,
          MPTimeData = mpdata.MPTimeData.map(timeData => ecmplist.v01p10.MPTimeData(
            DateFrom =Helper.toCalendar(getProcessDate1(Some(1)).toString),
            DateTo = timeData.DateTo,
            EnergyDirection = timeData.EnergyDirection,
            ECPartFact = timeData.ECPartFact,
            PlantCategory = timeData.PlantCategory,
            DateActivate = timeData.DateActivate,
            DateDeactivate = timeData.DateDeactivate,
            ECShare = timeData.ECShare,
            ECShC = timeData.ECShC))
        )))))
  }

  def withMeterList(mList: Option[Seq[Meter]]): ECMPListV0110Document =
    copy(doc=doc.copy(ProcessDirectory =
      doc.ProcessDirectory.copy(MPListData = mList match {
        case Some(ml) => ml.map(m=>ecmplist.v01p10.MPListData(
          MeteringPoint = m.meteringPoint,
          MPTimeData = Seq(ecmplist.v01p10.MPTimeData(
            DateFrom = Helper.toCalendar(buildCalendarDate(m.from.getOrElse(getProcessDate.getTime))),
            DateTo = Helper.toCalendar(m.to.map(buildCalendarDate).getOrElse("2099-12-31")),
            EnergyDirection = m.direction match {
              case Some(MeterDirectionType.CONSUMPTION) => ecmplist.v01p10.CONSUMPTION
              case _ => ecmplist.v01p10.GENERATION
            },
            ECPartFact = m.partFact.get,
            DateActivate = Helper.toCalendar(MessageHelper.buildCalendarDate(m.activation.get)),
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
      messageCodeVersion = Some("02.00"),
      ecId = Some(doc.ProcessDirectory.ECID),
      meterList = Some(doc.ProcessDirectory.MPListData
        .flatMap(m =>
          m.MPTimeData.map(mp =>
            Meter(
              meteringPoint = m.MeteringPoint,
              direction = Some(MeterDirectionType.withName(mp.EnergyDirection.toString)),
              activation = Some(mp.DateActivate.toGregorianCalendar.getTime),
              partFact = Some(mp.ECPartFact),
              from = Some(mp.DateFrom.toGregorianCalendar.getTime),
              to = Some(mp.DateTo.toGregorianCalendar.getTime),
              share = mp.ECShare,
              plantCategory = mp.PlantCategory,
              consentId = m.ConsentId
            ))
        )
      )
    )
  }
}

object ECMPListV0110Document {
  val processDate: GregorianCalendar = getProcessDate

  def apply(doc: ecmplist.v01p10.ECMPList) = new ECMPListV0110Document(doc)

  def apply(message: EbMsMessage): ECMPListV0110Document = new ECMPListV0110Document(
    doc = ecmplist.v01p10.ECMPList(
      MarketParticipantDirectory=ecmplist.v01p10.MarketParticipantDirectory(
      RoutingHeader=commontypes.v01p20.RoutingHeader(
        commontypes.v01p20.RoutingAddress(message.sender, Map(("@AddressType", scalaxb.DataRecord[commontypes.v01p20.AddressType](commontypes.v01p20.ECNumber)))),
        commontypes.v01p20.RoutingAddress(message.receiver, Map(("@AddressType", scalaxb.DataRecord[commontypes.v01p20.AddressType](commontypes.v01p20.ECNumber)))),
        Helper.toCalendar(buildCalendar(new Date))
      ),
      Sector=commontypes.v01p20.Number01,
      MessageCode=message.messageCode.toString,
      attributes=Map(
        ("@DocumentMode", scalaxb.DataRecord[commontypes.v01p20.DocumentMode](Config.interfaceMode match {
          case "SIMU" => commontypes.v01p20.SIMU
          case _ => commontypes.v01p20.PROD
        })),
        ("@Duplicate", scalaxb.DataRecord(false)),
        ("@SchemaVersion", scalaxb.DataRecord[ecmplist.v01p10.SchemaVersion](ecmplist.v01p10.Number01u4610)),
      )
    ),
      ProcessDirectory=ecmplist.v01p10.ProcessDirectory(
        MessageId = message.messageId.get,
        ConversationId = message.conversationId,
        ProcessDate = Helper.toCalendar(getProcessDate1(Some(1)).toString),
        ECID = message.ecId.get,
        ECType = message.ecType match {
          case Some(EcTypeEnum.GEA) => ecmplist.v01p10.GC
          case Some(EcTypeEnum.REGIONAL) => ecmplist.v01p10.RC_R
          case Some(EcTypeEnum.BEG) => ecmplist.v01p10.CC
          case _ => ecmplist.v01p10.RC_L
        },
        ECDisModel = message.ecDisModel match {
          case Some(EcDisModelEnum.STATIC) => ecmplist.v01p10.S
          case _ => ecmplist.v01p10.D
        },
      )
    )
  )
}
