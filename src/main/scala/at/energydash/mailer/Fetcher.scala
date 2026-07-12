package at.energydash.mailer

import at.energydash.config.Config
import at.energydash.domain.XmlParseHandler.mapXmlToEbms
import at.energydash.domain.dao.{EmailOutbox, SlickEmailOutboxRepository}
import at.energydash.domain.enums.EbMsMessageType
import at.energydash.domain.{EbMsMessage, XmlParseHandler}
import org.slf4j.LoggerFactory

import java.io.{File, FileOutputStream}
import java.sql.Timestamp
import java.util.zip.GZIPOutputStream
import java.util.{Calendar, GregorianCalendar, TimeZone}
import javax.mail._
import javax.mail.internet.{MimeBodyPart, MimeMultipart}
import javax.mail.search._
import scala.util.{Failure, Success, Try}

class Fetcher {

  import Fetcher._
  import ponton.`package`.fromAnySchemaType

  val logger = LoggerFactory.getLogger(classOf[Fetcher])

  private def createAndTerm(subject: String): SearchTerm = new OrTerm(new AndTerm(Array(new SubjectTerm(""), Fetcher.UNSEENTERM)), Fetcher.SEENTERM)
  //  private def createAndTerm(subject: String) : SearchTerm = new AndTerm(Array(new SubjectTerm(""), Fetcher.UNSEENTERM))
  private def persistenceFileName(tenant: String, m: Message) = {
    val receiveDate = new GregorianCalendar(TimeZone.getTimeZone("Europe/Vienna"))
    receiveDate.setTime(m.getReceivedDate)
    val path = f"${Config.emailPersistInbox}/${tenant}/${receiveDate.get(Calendar.YEAR)}/${receiveDate.get(Calendar.WEEK_OF_YEAR)}%02d"
    val name = s"${m.getReceivedDate.toString}_-${m.getSubject}-${m.getMessageNumber}.eml.gz"

    (new File(path), name)
  }

  private def prepareFile(tenant: String, m: Message) = {
    val (path, name) = persistenceFileName(tenant, m)
    if (!path.exists()) path.mkdirs()

    (new File(path, name), s"${path.getPath}/$name")
  }
  def persist(tenant: String, msgs: Array[Message])(implicit ctx: FetcherContext): Array[Message] = {
    msgs.foreach(m => if (m.getSize > 0) {
//      val dataOutputStream = new ByteArrayOutputStream()
//      m.writeTo(dataOutputStream)
//      m.writeTo(new FileOutputStream(new File(s"${Config.emailPersistInbox}/${m.getReceivedDate.toString}_${m.getSubject}-${m.getMessageNumber}.eml")))

      val (file, fileName) = prepareFile(tenant, m)

      val gzipFile = new GZIPOutputStream(new FileOutputStream(file))
      m.writeTo(gzipFile)
      gzipFile.flush()
      gzipFile.close()

//      ctx.mailRepo.create(EmailOutbox(None, tenant, m.getSubject, dataOutputStream.toByteArray, new Timestamp(System.currentTimeMillis())))
      ctx.mailRepo.create(EmailOutbox(None, tenant, m.getSubject, fileName.getBytes(), new Timestamp(m.getReceivedDate.getTime)))
    })
    msgs
  }

  def persistMail(tenant: String, msg: Message)(implicit ctx: FetcherContext): Message = {
    if (msg.getSize > 0) {
      val (file, fileName) = prepareFile(tenant, msg)
      msg.writeTo(new GZIPOutputStream(new FileOutputStream(file)))
      ctx.mailRepo.create(EmailOutbox(None, tenant, msg.getSubject, fileName.getBytes(), new Timestamp(msg.getReceivedDate.getTime)))
    }
    msg
  }

  //  def openInbox(): Folder = {
  //    Fetcher.store.connect(Config.imapHost, Config.imapUser, Config.imapPwd)
  //    val inbox = Fetcher.store.getFolder("Inbox")
  //    inbox.open(Folder.READ_WRITE)
  //    inbox
  //  }

  def getStore(session: Session): Store = {
    val store = session.getStore()

    store.connect()
    store
  }

  def fetch(searchTerm: String, distributeMessage: MailContent => Unit)(implicit ctx: FetcherContext): Unit = {
    val store = getStore(ctx.session)
    val inbox = store.getFolder("Inbox")
    inbox.open(Folder.READ_WRITE)

    val messages = inbox.search(createAndTerm(searchTerm))
    logger.info(s"[${ctx.tenant}] Emails found ${messages.length}")

    persist(ctx.tenant, messages).toList.foreach(m => buildMessage(m) match {
      case Success((m, c)) => c match {
        case Some(content) =>
          content match {
            case msg @ (_:MailMessage | _:ErrorMessage)  =>
              distributeMessage(msg)
              deleteEmail(m, inbox)
            case _ =>
          }
        case None =>
          deleteEmail(m, inbox)
      }
      case Failure(e) =>
        logger.error(s"Extract Message: $e")
        deleteEmail(m, inbox)
    })
    inbox.close(true)
    store.close()
  }

  def withAttachement(content: Message): List[MimeBodyPart] = content.getContent match {
    case multipart: MimeMultipart => List.range(0, multipart.getCount)
      .map(i => multipart.getBodyPart(i).asInstanceOf[MimeBodyPart])
      .filter(isAttachment)
    case _ => List.empty
  }

  def buildMessage(m: Message): Try[Tuple2[Message, Option[MailContent]]] = {
    Try {
      parseSubject(m.getSubject) match {
        case Some(("ERROR", "", "")) =>
          (m, Some(ErrorMessage(m.getHeader("Message-ID").toList.head, "ERROR",
            withAttachement(m).take(1).map(body => utils.SafeXml.loader.load(body.getInputStream)) match {
              case List(x) => EbMsMessage(
                messageCode = EbMsMessageType.ERROR_MESSAGE,
                messageCodeVersion = Some("01.00"),
                conversationId = "1",
                messageId = None,
                sender = "",
                receiver = "",
                errorMessage = Some((x \ "ReasonText").text))
              case _ => EbMsMessage(
                messageCode = EbMsMessageType.ERROR_MESSAGE,
                messageCodeVersion = Some("01.00"),
                conversationId = "1",
                messageId = None,
                sender = "",
                receiver = "",
                errorMessage = Some(s"No Attachment for ERROR Mail. Subject: ${m.getSubject}"))
            }
          )))
        case Some((protocol, version, messageId)) =>
          withAttachement(m).take(1) match {
            case List(body) => (m, Some(MailMessage(
              m.getHeader("Message-ID").toList.head,
              m.getFrom.head.toString,
              protocol,
              messageId,
              mapXmlToEbms(XmlParseHandler.ParseHeader("", "", None, Some(protocol)),
              utils.SafeXml.loader.load(body.getInputStream))
            )))
            //            case List(body) => MessageHelper.getEdaMessageFromHeader(EbMsProcessType.withName(protocol), version).map(
            //              msg => msg.fromXML(utils.SafeXml.loader.load(body.getInputStream)) match {
            //                case Success(value) =>
            //                  (m, Some(
            //                    MailMessage(
            //                      m.getHeader("Message-ID").toList.head,
            //                      m.getFrom.head.toString,
            //                      protocol,
            //                      messageId,
            //                      value
            //                    )
            //                  ))
            //                case Failure(exception) =>
            //                  logger.error(s"Extract Attachements: ${exception.getMessage}")
            //                  (m, Some(ErrorParseMessage(exception.getMessage)))
            //              }).getOrElse((m, None))
            case _ => (m, Some(ErrorMessage(
              m.getHeader("Message-ID").toList.head,
              "ERROR", EbMsMessage(
                messageCode = EbMsMessageType.ERROR_MESSAGE,
                messageCodeVersion = Some("01.00"),
                conversationId = "1",
                messageId = None,
                sender = "",
                receiver = "",
                errorMessage = Some(s"No Attachment in Mail with Subject ${m.getSubject}")
              )
            )))
//            case _ => (m, None)
          }
        case _ => (m, None)
      }
    }
  }

//  def buildMessage_old(m: Message): Either[Message, MailContent] = {
//    parseSubject(m.getSubject) match {
//      case Some(("ERROR", "", "")) =>
//        Right(ErrorMessage(m.getHeader("Message-ID").toList.head, "ERROR",
//          withAttachement(m).take(1).map(body => utils.SafeXml.loader.load(body.getInputStream)) match {
//            case List(x) => EdaErrorMessage.fromXML(x) match {
//              case Success(e) => e
//              case Failure(exception) => EdaErrorMessage(EbMsMessage(messageCode = EbMsMessageType.ERROR_MESSAGE, conversationId = "1", messageId = None,
//                sender = "", receiver = "", errorMessage = Some(exception.getMessage)))
//            }
//            case _ => EdaErrorMessage.fromXML(<EDASendError>
//              <ReasonText>No Attachement</ReasonText>
//            </EDASendError>) match {
//              case Success(e) => e
//              case Failure(exception) => EdaErrorMessage(EbMsMessage(messageCode = EbMsMessageType.ERROR_MESSAGE, conversationId = "1", messageId = None,
//                sender = "", receiver = "", errorMessage = Some(exception.getMessage)))
//            }
//          }))
//      case Some((protocol, version, messageId)) =>
//        withAttachement(m) match {
//          case List(body) => MessageHelper.getEdaMessageFromHeader(EbMsProcessType.withName(protocol), version).map(
//            msg => msg.fromXML(utils.SafeXml.loader.load(body.getInputStream)) match {
//              case Success(value) =>
//                Right(
//                  MailMessage(
//                    m.getHeader("Message-ID").toList.head,
//                    m.getFrom.head.toString,
//                    protocol,
//                    messageId,
//                    value
//                  )
//                )
//              case Failure(exception) =>
//                logger.error(s"Extract Attachements: ${exception.getMessage}")
//                Left(m)
//            }).getOrElse(Left(m))
//          case _ => Right(ErrorMessage(
//            m.getHeader("Message-ID").toList.head,
//            "ERROR", EdaErrorMessage.fromXML(
//              <EDASendError>
//                <ReasonText>No Attachement</ReasonText>
//              </EDASendError>) match {
//              case Success(e) => e
//              case Failure(exception) => EdaErrorMessage(EbMsMessage(messageCode = EbMsMessageType.ERROR_MESSAGE, conversationId = "1", messageId = None,
//                sender = "", receiver = "", errorMessage = Some(exception.getMessage)))
//            }))
//        }
//      case _ => Left(m)
//    }
//  }

  def parseSubject(subject: String): Option[(String, String, String)] = {
//    val pattern = """\[([A-Za-z_-]*)(?:_\d+\.\d+){0,1} MessageId=(.*)\]""".r
    val pattern = """\[([A-Za-z_-]*)(_(\d+\.\d+)){0,1} MessageId=(.*)\]""".r
    try {
      val pattern(protocol, _, version, messageId) = subject
      logger.info(s"Mail Subject (${subject}): Protocol: ${protocol} Version: ${version}")
      if (protocol.isEmpty || messageId.isEmpty) None else Some(protocol, version, messageId)
    } catch {
      case e: MatchError =>
        logger.error(s"Error Subject (${subject}): ${e.getMessage()}")
        Some("ERROR", "", "")
      case _: Throwable =>
        None
    }
  }

  //  def getProtocol(subject: String): Option[String] = {
  //    val pattern = """\[([A-Za-z_-]*) MessageId=.*\]""".r
  //    println(s"subject: ${subject}")
  //    try {
  //      val pattern(protocol) = subject.toString
  //      if(protocol.isEmpty) None else Some(protocol)
  //    } catch {
  //      case e:MatchError => None
  //    }
  //  }

  def extractAttachments(multiPart: Multipart): List[MimeBodyPart] = {
    List.range(0, multiPart.getCount)
      .map(i => multiPart.getBodyPart(i).asInstanceOf[MimeBodyPart])
      .filter(isAttachment)
  }

  def isAttachment(b: MimeBodyPart): Boolean = {
    Part.ATTACHMENT.equalsIgnoreCase(b.getDisposition)
  }

  def deleteEmail(m: Message, inbox: Folder): Unit = {
    val id = m.getHeader("Message-ID").toList.head
    try {
      val term = new MessageIDTerm(id)
      inbox.search(term).foreach(m => {
        m.setFlag(Flags.Flag.DELETED, true)
      })
      logger.info(s"Delete Email with id ${id}")
    } catch {
      case e: Exception => {
        logger.error(s"174 Error deleting Mail: $e - ${e.getMessage}")
      }
    }
  }

  def deleteById(id: String)(implicit ctx: FetcherContext): Unit = {
    val store = getStore(ctx.session)
    val inbox = store.getFolder("Inbox")
    inbox.open(Folder.READ_WRITE)
    try {
      val term = new MessageIDTerm(id)
      inbox.search(term).foreach(m => {
        m.setFlag(Flags.Flag.DELETED, true)
      })
      logger.info(s"Delete Email with id ${id}")
    } catch {
      case e: Exception => {
        logger.error(s"194 Error deleting Mail: $e - ${e.getMessage}")
      }
    } finally {
      inbox.close(true)
      store.close()
    }
  }
}

object Fetcher {

  def apply() = new Fetcher()

  val UNSEENTERM = new FlagTerm(new Flags(Flags.Flag.SEEN), false)
  val SEENTERM = new FlagTerm(new Flags(Flags.Flag.SEEN), true)

  case class EmailEnvelop(id: String, subject: String, protocol: Option[String], msg: Message, content: Multipart)

  trait MailContent

  case class MailMessage(id: String, from: String, protocol: String, messageId: String, content: EbMsMessage) extends MailContent

  case class ErrorMessage(id: String, protocol: String, content: EbMsMessage) extends MailContent
  case class ErrorParseMessage(message: String) extends MailContent

  case class FetcherContext(tenant: String, session: Session, mailRepo: SlickEmailOutboxRepository)

  sealed trait MailerResponseValue

  case class MailMessageList(response: List[MailMessage]) extends MailerResponseValue

  case class ErrorMessageList(response: List[ErrorMessage]) extends MailerResponseValue

}
