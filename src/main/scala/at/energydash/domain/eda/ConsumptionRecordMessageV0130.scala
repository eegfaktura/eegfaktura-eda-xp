package at.energydash.domain.eda

import at.energydash.domain._


//case class ConsumptionRecordMessage(message: EbMsMessage) extends EdaMessage {
//  override def getVersion(version: Option[String]) = EdaErrorXMLMessage(message)
//}
//
//object ConsumptionRecordMessageV0130 extends EdaResponseType {
//  def fromXML(xmlFile: Elem): Try[ConsumptionRecordMessage] = {
//    Try(scalaxb.fromXML[consumptionrecord.v01p30.ConsumptionRecord](xmlFile)).map(document =>
//      ConsumptionRecordMessage(
//        EbMsMessage(
//          messageId = Some(document.ProcessDirectory.MessageId),
//          conversationId = document.ProcessDirectory.ConversationId,
//          sender = document.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
//          receiver = document.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
//          messageCode = EbMsMessageType.withName(document.MarketParticipantDirectory.MessageCode.toString),
//          messageCodeVersion = Some("01.30"),
//          meter = Some(Meter(document.ProcessDirectory.MeteringPoint, None)),
//          energy = Some(document.ProcessDirectory.Energy.map(energy => Energy(
//            energy.MeteringPeriodStart.toGregorianCalendar.getTime,
//            energy.MeteringPeriodEnd.toGregorianCalendar.getTime,
//            energy.MeteringIntervall.toString,
//            energy.NumberOfMeteringIntervall,
//            data=energy.EnergyData.map(v =>
//                EnergyData(
//                  v.MeterCode,
//                  v.EP.map(vv => EnergyValue(
//                    vv.DTF.toGregorianCalendar.getTime,
//                    vv.DTT.map(dtt=>dtt.toGregorianCalendar.getTime),
//                    vv.MM.map(mm => mm.toString),
//                    vv.BQ
//                ))
//              )
//            )
//          )).head
//          ),
//        )
//      )
//    )
//  }
//}

//object ConsumptionRecordMessageV0303 extends EdaResponseType {
//  def fromXML(xmlFile: Elem): Try[ConsumptionRecordMessage] = {
//    Try(scalaxb.fromXML[consumptionrecord.v01p31.ConsumptionRecord](xmlFile)).map(document =>
//      ConsumptionRecordMessage(
//        EbMsMessage(
//          messageId = Some(document.ProcessDirectory.MessageId),
//          conversationId = document.ProcessDirectory.ConversationId,
//          sender = document.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
//          receiver = document.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
//          messageCode = EbMsMessageType.withName(document.MarketParticipantDirectory.MessageCode.toString),
//          messageCodeVersion = Some("01.31"),
//          meter = Some(Meter(document.ProcessDirectory.MeteringPoint, None)),
//          energy = Some(document.ProcessDirectory.Energy.map(energy => Energy(
//            energy.MeteringPeriodStart.toGregorianCalendar.getTime,
//            energy.MeteringPeriodEnd.toGregorianCalendar.getTime,
//            energy.MeteringIntervall.toString,
//            energy.NumberOfMeteringIntervall,
//            data=energy.EnergyData.map(v =>
//              EnergyData(
//                v.MeterCode,
//                v.EP.map(vv => EnergyValue(
//                  vv.DTF.toGregorianCalendar.getTime,
//                  Some(vv.DTT.toGregorianCalendar.getTime),
//                  vv.MM.map(mm => mm.toString),
//                  vv.BQ
//                ))
//              )
//            )
//          )).head),
//        )
//      )
//    )
//  }
//}

//object ConsumptionRecordMessageV0410 extends EdaResponseType {
//  def fromXML(xmlFile: Elem): Try[ConsumptionRecordMessage] = {
//    Try(scalaxb.fromXML[consumptionrecord.v01p40.ConsumptionRecord](xmlFile)).map(document =>
//      ConsumptionRecordMessage(
//        EbMsMessage(
//          messageId = Some(document.ProcessDirectory.MessageId),
//          conversationId = document.ProcessDirectory.ConversationId,
//          sender = document.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
//          receiver = document.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
//          messageCode = EbMsMessageType.withName(document.MarketParticipantDirectory.MessageCode.toString),
//          messageCodeVersion = Some("01.40"),
//          ecId = document.ProcessDirectory.ECID,
//          meter = Some(Meter(document.ProcessDirectory.MeteringPoint, None)),
//          energy = Some(document.ProcessDirectory.Energy.map(energy => Energy(
//            energy.MeteringPeriodStart.toGregorianCalendar.getTime,
//            energy.MeteringPeriodEnd.toGregorianCalendar.getTime,
//            energy.MeteringIntervall.toString,
//            energy.NumberOfMeteringIntervall,
//            data=energy.EnergyData.map(v =>
//              EnergyData(
//                v.MeterCode,
//                v.EP.map(vv => EnergyValue(
//                  vv.DTF.toGregorianCalendar.getTime,
//                  Some(vv.DTT.toGregorianCalendar.getTime),
//                  Some(vv.MM.toString),
//                  vv.BQ
//                ))
//              )
//            )
//          )).head),
//        )
//      )
//    )
//  }
//}