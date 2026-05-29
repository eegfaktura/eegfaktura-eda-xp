package at.energydash.domain.eda

import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType._
import at.energydash.domain.enums.EbMsProcessType
import at.energydash.domain.enums.EbMsProcessType.EbMsProcessType
import at.energydash.utils.Base58
import at.energydash.utils.zip.CRC8
import org.slf4j.{Logger, LoggerFactory}

import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.{LocalDate, ZoneId}
import java.util.zip.CRC32
import java.util._
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}


object MessageHelper {

  var logger: Logger = LoggerFactory.getLogger(this.getClass)

    implicit def toOptionSE(t: Try[EdaXMLMessage[_]]): Option[EdaXMLMessage[_]] = t match {
      case Success(s) => Some(s)
      case Failure(ex) =>
        println(s"SEPP############### $ex")
        logger.error(ex.getMessage, ex)
        None
    }

  /**
   * Extract Message Type for Sending to Marktteilnehmer.
   */
  def getEdaMessageByType(message: EbMsMessage): Option[EdaXMLMessage[_]] = {
    (message.messageCode match {
      case ONLINE_REG_INIT => CMRequestRegistrationOnline(message).getVersion()
      case OFFLINE_REG_INIT => CMRequestOfflineRegistration(message).getVersion()
      case ZP_LIST => CPRequestZPList(message).getVersion()
      case EEG_BASE_DATA => CPRequestBaseData(message).getVersion()
      case ENERGY_SYNC_REQ => CPRequestMeteringValue(message).getVersion()
      case EDA_MSG_AUFHEBUNG_CCMS => CMRevokeRequest(message).getVersion()
      case CHANGE_METER_PARTITION => ECPartitionChangeMessage(message).getVersion()
      case _ => None
    })
//    matcn {
//      case Success(obj) => Some(obj)
//      case Failure(exception) => None
//
//    }
  }

  //  /**
  //   * Lookup for Message object according to Message type. Incoming messages.
  //   * @param processCode
  //   * @param version
  //   * @return
  //   */
  //  def getEdaMessageFromHeader(processCode: EbMsProcessType, version: String): Option[EdaResponseType] = {
  //    processCode match {
  //      case PROCESS_ENERGY_RESPONSE => {
  //        version match {
  //          case "03.03" => Some(ConsumptionRecordMessageV0303)
  //          case "03.10" => Some(ConsumptionRecordMessageV0410)
  //          case _ => Some(ConsumptionRecordMessageV0130)
  //        }
  //      }
  //      case EbMsProcessType.PROCESS_REGISTER_ONLINE =>
  //        version match {
  //          case "02.00" => Some(CMRequestRegistrationOnlineXMLMessageV0200)
  //          case _ => Some(CMRequestRegistrationOnlineXMLMessageV0110)
  //        }
  //      case EbMsProcessType.PROCESS_REGISTER_OFFLINE => Some(CMRequestOfflineRegistrationXMLMessage)
  //      case EbMsProcessType.PROCESS_LIST_METERINGPOINTS => Some(CPRequestZPListXMLMessage)
  //      case EbMsProcessType.PROCESS_METERINGPOINTS_VALUE => Some(CPRequestMeteringValueXMLMessage)
  //      case EbMsProcessType.PROCESS_REVOKE_VALUE | EbMsProcessType.PROCESS_REVOKE_CUS => Some(CMRevokeXMLMessageV0100)
  //      case EbMsProcessType.PROCESS_REVOKE_SP => Some(CMRevokeRequestV0100)
  //      case EbMsProcessType.PROCESS_EC_PRTFACT_CHANGE => {
  //        version match {
  //          case "01.00" => Some(ECPartitionChangeXMLMessage)
  //          case _ => Some(EdaWrongVersionXMLMessage)
  //        }
  //      }
  //      case _ =>
  //        logger.warn(s"Wrong ProcessCode: ${processCode}")
  //        None
  //    }
  //  }

  def EDAMessageCodeToProcessCode(msCode: EbMsMessageType): EbMsProcessType = {
    msCode match {
      case ENERGY_FILE_RESPONSE => EbMsProcessType.PROCESS_ENERGY_RESPONSE
      case ZP_LIST | ZP_LIST_RESPONSE | ZP_LIST_REJECTION => EbMsProcessType.PROCESS_LIST_METERINGPOINTS
      case EEG_BASE_DATA | EEG_BASE_RESPONSRE | EEG_BASE_REJECTION => EbMsProcessType.PROCESS_MASTER_DATA
      case ONLINE_REG_INIT | ONLINE_REG_ANSWER | ONLINE_REG_ABORT | ONLINE_REG_REJECTION | ONLINE_REG_APPROVAL | ONLINE_REG_COMPLETION => EbMsProcessType.PROCESS_REGISTER_ONLINE
      case OFFLINE_REG_INIT | OFFLINE_REG_ANSWER | OFFLINE_REG_ABORT | OFFLINE_REG_REJECTION | OFFLINE_REG_APPROVAL | OFFLINE_REG_COMPLETION => EbMsProcessType.PROCESS_REGISTER_OFFLINE
      case ENERGY_SYNC_REQ | ENERGY_SYNC_RES | ENERGY_SYNC_REJECTION => EbMsProcessType.PROCESS_METERINGPOINTS_VALUE
      case EDA_MSG_AUFHEBUNG_CCMI => EbMsProcessType.PROCESS_REVOKE_VALUE
      case EDA_MSG_AUFHEBUNG_CCMC => EbMsProcessType.PROCESS_REVOKE_CUS
      case EDA_MSG_AUFHEBUNG_CCMS | EDA_MSG_ANTWORT_CCMS | EDA_MSG_ABLEHNUNG_CCMS => EbMsProcessType.PROCESS_REVOKE_SP
      case CHANGE_METER_PARTITION | CHANGE_METER_PARTITION_REJECTION | CHANGE_METER_PARTITION_ANSWER => EbMsProcessType.PROCESS_EC_PRTFACT_CHANGE
      case _ => throw new RuntimeException(s"Not able to find Message -> Process mapping ${msCode.toString}")
    }
  }

  def buildRequestId(messageId: String): String = {
    val crc32 = new CRC32()
    crc32.update(messageId.getBytes)
    val crc32Val = crc32.getValue

    val crc8 = new CRC8
    crc8.reset()
    crc8.update(BigInt(crc32Val).toByteArray)

    val compose = BigInt((crc32Val << 8) + crc8.getValue).toByteArray
    Base58.encode(compose.takeRight(5))
  }

  def buildMessageId(participant: String, seqNumber: Long): String = {
    val cal = Calendar.getInstance
    val dateTime = cal.getTime

    val dateFormat = new SimpleDateFormat("dd")
    val date = dateFormat.format(dateTime)

    val monthFormat = new SimpleDateFormat("MM")
    val month = monthFormat.format(dateTime)

    val yearFormat = new SimpleDateFormat("YYYY")
    val year = yearFormat.format(dateTime)

    s"${participant}${year}${month}${date}${dateTime.getTime / 10000}${formatSeqNumber(seqNumber)}"
  }

  def formatSeqNumber(seqNumber: Long) = f"${seqNumber}%010d"

  def buildCalendarNow(): GregorianCalendar = new GregorianCalendar(new Locale("de", "AT"))

  def buildCalendar(date: Date): GregorianCalendar = {
    val calendar: GregorianCalendar = buildCalendarNow()
    calendar.setTime(date)
    calendar.set(Calendar.MILLISECOND, 0)
    calendar
  }

  def buildCalendarDate(date: Date): String = {
//    val format = new SimpleDateFormat("yyyy-MM-dd", new Locale("de", "AT"))
//    format.format(date)

//    val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", new Locale("de", "AT"))
//    format.setTimeZone(TimeZone.getTimeZone("CET"));
//    format.format(date)

    date.toInstant.atZone(ZoneId.of("Europe/Vienna")).toLocalDate.toString
  }


//  def buildCalendarDate(date: Date): GregorianCalendar = {
//    //    val format = new SimpleDateFormat("yyyy-MM-dd", new Locale("de", "AT"))
//    //    format.format(date)
//
//    //    val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", new Locale("de", "AT"))
//    //    format.setTimeZone(TimeZone.getTimeZone("CET"));
//    //    format.format(date)
//
//    GregorianCalendar.from(
//      date.toInstant.atZone(ZoneId.of("Europe/Vienna"))
//    )
//  }

//  def buildCalendarDateFromString(d: String): GregorianCalendar =

  def getProcessDate: String = {
    //    val processDate = new GregorianCalendar(TimeZone.getTimeZone("Europe/Vienna"), new Locale("de", "AT"))
    //    processDate.add(Calendar.DAY_OF_MONTH, 1)
    //    processDate
    //      val z = ZoneId.of("Europe/Vienna")
    //      GregorianCalendar.from(
    //        LocalDate.now(z).plusDays(1).atStartOfDay(z)
    //      )
    getNow(Some(1L)).toString
  }

  def getNow(add: Option[Long] = None): LocalDate = {
    val z = ZoneId.of("Europe/Vienna")
    LocalDate.now(z).plusDays(add.getOrElse(0L))
  }

  def formatLocalDate(l: LocalDate):String = l.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

  def getProcessTime(message: String): GregorianCalendar = {
    val processDate = new GregorianCalendar(new Locale("de", "AT"))
    processDate.set(Calendar.MILLISECOND, 0)

    message match {
      case "ANFORDERUNG_CPF" =>
        processDate.add(Calendar.DAY_OF_MONTH, 1)
        if (processDate.get(Calendar.HOUR_OF_DAY) > 16) {
          processDate.set(Calendar.HOUR_OF_DAY, 16)
          processDate.set(Calendar.MINUTE, 59)
          processDate.set(Calendar.SECOND, 0)
        }
      case _ =>
        processDate.add(Calendar.DATE, 1)
    }
    processDate
  }
}
