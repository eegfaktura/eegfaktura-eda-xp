package at.energydash.service

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.model.Multipart.BodyPart
import org.apache.pekko.http.scaladsl.model.{BodyPartEntity, Multipart}
import org.apache.pekko.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.{ByteString, Timeout}
import at.energydash.actors.MqttPublisher.{EdaNotification, MqttCommand, MqttPublish}
import at.energydash.domain.eda.EdaErrorMessage
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.domain.{DefaultEbMsMessage, EbMsMessage, XmlParseHandler}
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

trait FileService {
  implicit val system: ActorSystem[_]
  implicit val mat: Materializer

  def handleUpload(formData: Multipart.FormData, ecId: Option[String]): Future[Done]
}

object FileService {
  def apply(system: ActorSystem[_], mqttPublisher: ActorRef[MqttCommand])(implicit mat: Materializer) =
    new FileServiceImpl(system, mqttPublisher)
}

class FileServiceImpl(val system: ActorSystem[_], mqttPublisher: ActorRef[MqttCommand])(implicit val mat: Materializer) extends FileService with StrictLogging {

  import system._
  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit val sched: Scheduler = system.scheduler
  import ponton.`package`.fromAnySchemaType

  implicit def bp2sting(implicit ev: Unmarshaller[String, String]): Unmarshaller[BodyPartEntity, String] = Unmarshaller.withMaterializer { implicit executionContext =>
    implicit mat =>
      entity =>
        entity.dataBytes
          .runWith(Sink.fold(ByteString.empty)((accum, bs) => accum.concat(bs)))
          .map(_.decodeString(java.nio.charset.StandardCharsets.UTF_8))
          .flatMap(ev.apply(_))
  }

  private def bodyPart2String(body: BodyPart): Future[String] = Unmarshal(body.entity).to[String]

  private def bodyPart2Xml(body: BodyPart) = bodyPart2String(body).map(scala.xml.XML.loadString)

  private def edaErrorMessage(error: String) = {
    EdaErrorMessage(EbMsMessage(
      messageCode = EbMsMessageType.ERROR_MESSAGE,
      messageCodeVersion = Some("01.00"),
      conversationId = "1",
      messageId = None,
      sender = "",
      receiver = "",
      errorMessage = Some(error)
    ))
  }

  def parseProcessName(processName: String): Option[(String, String)] = {
    val pattern = """([A-Za-z_-]*)(_(\d+\.\d+)){0,1}""".r
    try {
      val pattern(protocol, _, version) = processName
      logger.info(s"Admin received Protocol: ${protocol} Version: ${version}")
      Some(protocol, version)
    } catch {
      case e: MatchError =>
        logger.error(s"Error ProcessInfo: ${e.getMessage()}")
        Some("ERROR", "")
      case _: Throwable =>
        None
    }
  }

  def handleUpload(formData: Multipart.FormData, ecId: Option[String]): Future[Done] = {
    formData.parts
      .map(part => FileInfo(part))
      .map(info => Tuple2(info, parseProcessName(info.processName)))
      .mapAsync(1) {
        case (info, Some(process)) => bodyPart2Xml(info.bodyPart).map(xml => {
          (process._1, XmlParseHandler.mapXmlToEbms(
            XmlParseHandler.ParseHeader("ADMIN", "ADMIN", MessageType = Some(process._1)), xml))
//          scalaxb.DataRecord(scalaxb.ElemName(xml)) match {
//            case DataRecord(_, _, x: v01p10.ECMPList) => (process._1, ECMPListV0110Document(x).toMessage)
//            case _ => ("error", edaErrorMessage("Unknown process type").message)
//          }
        })
        case (info, None) =>
          log.error(s"File not valid ${info.processName}")
          Future(("Error", DefaultEbMsMessage.Error("FileUpload", Some("Wrong File"))))
      }
      .map {
        case (processName, message) if ecId.isDefined =>
          Tuple2(processName, message.copy(ecId = ecId))
        case (processName, message) => (processName, message)
      }
      .map {
        case (processName, message) =>
          EdaNotification(processName, message) :: Nil
      }
      .map(p => mqttPublisher ! MqttPublish(p))
      .runWith(Sink.ignore)
    }

//  def mergeEbmsMessage(stored: Option[EbMsMessage], current: EbMsMessage): EbMsMessage = {
//    current.messageCode match {
//      case ENERGY_SYNC_REJECTION | ENERGY_SYNC_RES =>
//        current.copy(meter=stored.flatMap(_.meter), ecId = stored.flatMap(_.ecId))
//      case EDA_MSG_ABLEHNUNG_CCMS | EDA_MSG_ANTWORT_CCMS =>
//        current.copy(consentEnd = stored.flatMap(_.consentEnd), ecId = stored.flatMap(_.ecId))
//      case CHANGE_METER_PARTITION_ANSWER | CHANGE_METER_PARTITION_REJECTION =>
//        current.copy(meterList = stored.flatMap(_.meterList), ecId = stored.flatMap(_.ecId))
//      case ONLINE_REG_ANSWER | ONLINE_REG_REJECTION | ONLINE_REG_APPROVAL | ONLINE_REG_COMPLETION =>
//        current.copy(ecId = stored.flatMap(_.ecId))
//      case ZP_LIST_RESPONSE =>
//        current.copy(ecId = stored.flatMap(_.ecId))
//      case ENERGY_FILE_RESPONSE =>
//        current.copy(ecId = stored.flatMap(_.ecId))
//      case _ =>
//        current
//    }
//  }
}

