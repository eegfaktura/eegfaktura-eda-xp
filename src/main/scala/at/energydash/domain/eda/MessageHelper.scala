package at.energydash.domain.eda

import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsMessageType._
import at.energydash.domain.enums.EbMsProcessType
import at.energydash.domain.enums.EbMsProcessType.EbMsProcessType
import at.energydash.utils.Base58
import at.energydash.utils.zip.CRC8

import java.text.SimpleDateFormat
import java.time.{LocalDate, ZoneId}
import java.util.zip.CRC32
import java.util._
import scala.util.{Failure, Try}


object MessageHelper {

  /**
   * Extract Message Type for Sending to Marktteilnehmer.
   */
  // the reason is already logged where it arises
  def getEdaMessageByType(message: EbMsMessage): Option[EdaXMLMessage[_]] = getEdaMessageByTypeTry(message).toOption

  /**
   * Like [[getEdaMessageByType]], but keeps the reason when no document can be built (e.g. the
   * unknown version label), so the sender gets it instead of "No XML mapping".
   */
  def getEdaMessageByTypeTry(message: EbMsMessage): Try[EdaXMLMessage[_]] = {
    (message.messageCode match {
      case ONLINE_REG_INIT => CMRequestRegistrationOnline(message).getVersion()
      case OFFLINE_REG_INIT => CMRequestOfflineRegistration(message).getVersion()
      case ZP_LIST => CPRequestZPList(message).getVersion()
      case EEG_BASE_DATA => CPRequestBaseData(message).getVersion()
      case ENERGY_SYNC_REQ => CPRequestMeteringValue(message).getVersion()
      case EDA_MSG_AUFHEBUNG_CCMS => CMRevokeRequest(message).getVersion()
      case CHANGE_METER_PARTITION => ECPartitionChangeMessage(message).getVersion()
      case _ => Failure(new IllegalArgumentException(s"No XML mapping for message type ${message.messageCode}"))
    })
  }

  def EDAMessageCodeToProcessCode(msCode: EbMsMessageType): EbMsProcessType = {
    msCode match {
      case ENERGY_FILE_RESPONSE => EbMsProcessType.PROCESS_ENERGY_RESPONSE
      case ZP_LIST | ZP_LIST_RESPONSE | ZP_LIST_REJECTION => EbMsProcessType.PROCESS_LIST_METERINGPOINTS
      case EEG_BASE_DATA | EEG_BASE_RESPONSE | EEG_BASE_REJECTION => EbMsProcessType.PROCESS_MASTER_DATA
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

  /**
   * MessageType im Header des Ponton-EDA-Adapters. Richtig ist der Nachrichtentyp aus der
   * Schema-Set-Definition (ANFORDERUNG_ECP, ...), siehe Ponton Backend Integration Guide 2.1.1 und
   * README "Ponton header". Mit dem Prozesscode (EC_PODLIST) findet der Messenger fuer die Sets der
   * EDA-Umstellung 05.10.2026 kein Schema. PT und CCMS senden noch den Prozesscode und werden
   * angenommen (warum, ist ungeklaert); umstellen mit ihrer naechsten Versionsaenderung.
   */
  def pontonHeaderMessageType(msCode: EbMsMessageType): String = msCode match {
    case ZP_LIST | ONLINE_REG_INIT | OFFLINE_REG_INIT | CHANGE_METER_PARTITION => msCode.toString
    case _ => EDAMessageCodeToProcessCode(msCode).toString
  }

  def buildRequestId(messageId: String): String = {
    val crc32 = new CRC32()
    crc32.update(messageId.getBytes)
    val crc32Val = crc32.getValue

    val crc8 = new CRC8
    crc8.reset()
    crc8.update(BigInt(crc32Val).toByteArray)

    val compose = BigInt((crc32Val << 8) + crc8.getValue).toByteArray
    Base58.encode(compose.takeRight(5).toIndexedSeq)
  }

  def buildMessageId(participant: String, seqNumber: Long, dateTime: Date = new Date): String = {

    val dateFormat = new SimpleDateFormat("dd")
    val date = dateFormat.format(dateTime)

    val monthFormat = new SimpleDateFormat("MM")
    val month = monthFormat.format(dateTime)

    val yearFormat = new SimpleDateFormat("yyyy")
    val year = yearFormat.format(dateTime)

    s"${participant}${year}${month}${date}${dateTime.getTime / 10000}${formatSeqNumber(seqNumber)}"
  }

  def formatSeqNumber(seqNumber: Long) = f"${seqNumber}%010d"

  def buildCalendarNow(): GregorianCalendar = new GregorianCalendar(new Locale("de", "AT"))

  /**
   * xsd:dateTime with the real UTC offset of the calendar's zone, including daylight saving
   * time. scalaxb's Helper.toCalendar(GregorianCalendar) uses only the raw offset, so documents
   * built in Europe/Vienna carried +01:00 with the summer wall-clock time (one hour off).
   */
  def toXmlDateTime(calendar: GregorianCalendar): javax.xml.datatype.XMLGregorianCalendar =
    javax.xml.datatype.DatatypeFactory.newInstance().newXMLGregorianCalendar(calendar)

  def xmlDateTime(date: Date): javax.xml.datatype.XMLGregorianCalendar = toXmlDateTime(buildCalendar(date))

  def buildCalendar(date: Date): GregorianCalendar = {
    val calendar: GregorianCalendar = buildCalendarNow()
    calendar.setTime(date)
    calendar.set(Calendar.MILLISECOND, 0)
    calendar
  }

  def buildCalendarDate(date: Date): String = {
    date.toInstant.atZone(ZoneId.of("Europe/Vienna")).toLocalDate.toString
  }

  def getProcessDate: String = {
    getNow(Some(1L)).toString
  }

  def getNow(add: Option[Long] = None): LocalDate = {
    val z = ZoneId.of("Europe/Vienna")
    LocalDate.now(z).plusDays(add.getOrElse(0L))
  }
}
