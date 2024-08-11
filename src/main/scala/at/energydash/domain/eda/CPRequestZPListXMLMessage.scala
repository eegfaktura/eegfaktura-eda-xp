package at.energydash.domain.eda

import at.energydash.domain.enums.{EbMsMessageType, MeterDirectionType}
import at.energydash.domain.xml.CPRequestV0112Document
import at.energydash.domain.{EbMsMessage, Meter, ResponseData}
import cpnotification.v01p13.CPNotification
import ecmplist.v01p10.ECMPList
import ponton.`package`.{Cpnotificationv01p13_CPNotificationFormat, Cprequestv01p12_CPRequestFormat, Ecmplistv01p10_ECMPListFormat}
import scalaxb.{CanWriteXML, Helper}

import java.util.{Calendar, TimeZone}
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, NamespaceBinding, Node, TopScope}

case class CPRequestZPList(message: EbMsMessage) extends EdaMessage {
  override def getVersion(version: Option[String] = None): EdaXMLMessage[_] = CPRequestZPListXMLMessage(message)
}

case class CPRequestZPListXMLMessage(message: EbMsMessage) extends EdaXMLMessage[cprequest.v01p12.CPRequest] {
  import java.util.GregorianCalendar

  override implicit val edaTypeCanWrite: CanWriteXML[cprequest.v01p12.CPRequest] = Cprequestv01p12_CPRequestFormat
  override def rootNodeLabel: Some[String] = Some("CPRequest")

  override def schemaLocation: Option[String] = Some("http://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12 " +
    "http://www.ebutilities.at/schemata/customerprocesses/EC_PODLIST/02.00/ANFORDERUNG_ECP")

  def toDoc: cprequest.v01p12.CPRequest = CPRequestV0112Document(message)
    .withExtention(message.timeline.map(t => {
      val tz = TimeZone.getTimeZone("Europe/Vienna")
      val from = new GregorianCalendar(tz);from.setTime(t.from);from.set(Calendar.MILLISECOND, 0)
      val to = new GregorianCalendar(tz);to.setTime(t.to);to.set(Calendar.MILLISECOND, 0)
      cprequest.v01p12.Extension(
        DateTimeFrom = Some(Helper.toCalendar(from)),
        DateTimeTo = Some(Helper.toCalendar(to)),
        AssumptionOfCosts = false)
    })).toDoc()

  override def toScope: NamespaceBinding = scalaxb.toScope(
//    Some("cp") -> "http://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12",
    None -> "http://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12",
    Some("ct") -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance",
  )

//  def toXML: Node = {
//    scalaxb.toXML[cprequest.v01p12.CPRequest](toDoc, Some("http://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12"), rootNodeLabel,
//      toScope,
//      true).head
//  }
  def toXML: Node = {
    scalaxb.toXML[cprequest.v01p12.CPRequest](toDoc, schemaLocation, rootNodeLabel, toScope, true).head
  }

  private def defineNamespaceBinding(): NamespaceBinding = {
    val nsb2 = NamespaceBinding("schemaLocation", "http://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12/CPRequest_01p12.xsd", TopScope)
    val nsb3 = NamespaceBinding("xsi", "http://www.w3.org/2001/XMLSchema-instance", nsb2)
    val nsb4 = NamespaceBinding("cp", "http://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12", TopScope)
    NamespaceBinding(null, "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20", nsb2)
  }
}

//object CPRequestZPListXMLMessage extends EdaResponseType {
//  def fromXML(xmlFile: Elem): Try[CPRequestZPList] = {
//    resolveMessageCode(xmlFile) match {
//      case Success(mc) => mc match {
//        case EbMsMessageType.ZP_LIST_RESPONSE =>
//          Try(scalaxb.fromXML[ECMPList](xmlFile)).map(document =>
//            CPRequestZPList(
//              EbMsMessage(
//                messageId = Some(document.ProcessDirectory.MessageId),
//                conversationId = document.ProcessDirectory.ConversationId,
//                sender = document.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
//                receiver = document.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
//                messageCode = EbMsMessageType.withName(document.MarketParticipantDirectory.MessageCode.toString),
//                messageCodeVersion = Some("04.10"),
//                meterList = Some(document.ProcessDirectory.MPListData
//                  .flatMap(m =>
//                    m.MPTimeData.map(mp =>
//                      Meter(
//                        meteringPoint = m.MeteringPoint,
//                        direction = Some(MeterDirectionType.withName(mp.EnergyDirection.toString)),
//                        activation = Some(mp.DateActivate.toGregorianCalendar.getTime),
//                        partFact = Some(mp.ECPartFact),
//                        from = Some(mp.DateFrom.toGregorianCalendar.getTime),
//                        to = Some(mp.DateTo.toGregorianCalendar.getTime),
//                        share = mp.ECShare,
//                        plantCategory = mp.PlantCategory
//                      ))
//                  )
//                ),
//              )
//            )
//          )
//        case _ => Try(scalaxb.fromXML[CPNotification](xmlFile)).map(document =>
//          CPRequestZPList(
//            EbMsMessage(
//              messageId=Some(document.ProcessDirectory.MessageId),
//              conversationId=document.ProcessDirectory.ConversationId,
//              sender=document.MarketParticipantDirectory.RoutingHeader.Sender.MessageAddress,
//              receiver=document.MarketParticipantDirectory.RoutingHeader.Receiver.MessageAddress,
//              messageCode=EbMsMessageType.withName(document.MarketParticipantDirectory.MessageCode),
//              messageCodeVersion=Some("01.13"),
//              responseData = Some(document.ProcessDirectory.ResponseData.ResponseCode.map(r => ResponseData(None, List(r)))),
//            )
//          )
//        )
//      }
//      case Failure(exception) =>
//        Try(CPRequestZPList(
//          EbMsMessage(
//            messageCode = EbMsMessageType.ERROR_MESSAGE,
//            messageCodeVersion=Some("01.00"),
//            conversationId = "1",
//            messageId = None,
//            sender = "",
//            receiver = "",
//            errorMessage = Some(exception.getMessage)
//          )
//        ))
//    }
//  }
//}