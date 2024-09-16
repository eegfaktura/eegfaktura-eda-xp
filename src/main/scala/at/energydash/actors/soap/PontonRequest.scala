package at.energydash.actors.soap

//import akka.actor.ActorSystem
import akka.actor.typed.ActorSystem
import at.energydash.actors.PontonService.buildMessageId
import at.energydash.domain.EbMsMessage
import at.energydash.domain.eda.MessageHelper
import at.energydash.domain.eda.MessageHelper.EDAMessageCodeToProcessCode
import ponton.OutHeaderType
import ponton.`package`.__NodeXMLFormat
import scalaxb.{DataRecord, HttpClientsAsync, Soap11ClientsAsync}
import soapenvelope11.{Body, Envelope, Header}

import java.net.URI
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NamespaceBinding

trait OutboundDocument4SOAPBindings { this: scalaxb.Soap11ClientsAsync with scalaxb.HttpClientsAsync =>
  val scope: NamespaceBinding

  lazy val targetNamespace: Option[String] = Some("http://xp.ponton.de/eda/v320")
  lazy val service: OutboundDocument4SOAPBinding = new OutboundDocument4SOAPBinding {}
  def baseAddress = new java.net.URI("http://localhost/outboundDocument")

  trait OutboundDocument4SOAPBinding extends ponton.OutboundDocumentPort {
    import scalaxb.ElemName._

    def sendDocument(header: ponton.OutHeaderType, message: ponton.Message2)(implicit ec: ExecutionContext): Future[Unit] =
      soapClient.requestResponse(scalaxb.toXML(ponton.OutboundDocument(header, message), targetNamespace, "OutboundDocument", scope),
        Nil, scope, baseAddress, "POST", Some(new java.net.URI("http://xp.ponton.de/eda/v320/outboundDocument"))).map({ case x => () })

    def sendRequest(edaMessage: EbMsMessage)(implicit ec: ExecutionContext): Future[Envelope] /*Future[(scala.xml.NodeSeq, scala.xml.NodeSeq)]*/ = {
      val xmlObj = MessageHelper.getEdaMessageByType(edaMessage)
      val header = OutHeaderType(
        MessageId = buildMessageId(edaMessage.sender, edaMessage.seqNr.getOrElse(10000)),
        SenderId = edaMessage.sender,
        ReceiverId = edaMessage.receiver,
        MessageVersion = edaMessage.messageCodeVersion.getOrElse("01.00"),
        MessageType = EDAMessageCodeToProcessCode(edaMessage.messageCode).toString,
        LogInfo = Some("VFEEG-OUT"))
      val body = ponton.Message2(message2option = xmlObj.toRecord)

      val soapBody = scalaxb.toXML(
        ponton.OutboundDocument(header, body), targetNamespace, Some("OutboundDocument"),
        scalaxb.toScope(scalaxb.fromScope(scope).foldRight(scalaxb.fromScope(xmlObj.toScope)){ (a, b) => a :: b }.reverse : _*/*.distinct: _**/),
        typeAttribute = true)

      val bodyRecords = soapBody.toSeq map { DataRecord(None, None, _) }
      val headerOption = Nil.toSeq.headOption map { _ =>
        Header(Nil.toSeq map {DataRecord(None, None, _)}, Map())
      }
      println(s"HeaderOptions: $headerOption")
      val envelope = Envelope(None, Body(bodyRecords, Map()), Nil, Map())
      soapClient.soapRequest(Some(envelope), scope, baseAddress, "POST", Some(new java.net.URI("http://xp.ponton.de/eda/v320/outboundDocument")))
    }
  }
}

class PontonRequest(system: ActorSystem[_]) extends OutboundDocument4SOAPBindings with Soap11ClientsAsync { this: HttpClientsAsync =>

  implicit def actorSystem: ActorSystem[_] = system
  implicit val ec: ExecutionContext = system.executionContext

  override def baseAddress: URI = new java.net.URI("http://localhost:6090/pontonxp/message")

  override val scope: NamespaceBinding = scalaxb.toScope(
//    Some("cp") -> "http://www.ebutilities.at/schemata/customerprocesses/ecmplist/01p10",
//    Some("cp1") -> "http://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12",
//    Some("ct") -> "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20",
    Some("oe1") -> "http://www.ebutilities.at/datenplattform/0700",
//    Some("oe") -> "http://xp.ponton.de/eda/v320",
    Some("tns") -> "http://xp.ponton.de/eda/v320",
    Some("xs") -> "http://www.w3.org/2001/XMLSchema",
    Some("xsi") -> "http://www.w3.org/2001/XMLSchema-instance")

}
