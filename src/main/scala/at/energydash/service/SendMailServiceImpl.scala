package at.energydash.service

import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.util.{ByteString, Timeout}
import at.energydash.admin.mail.{SendMailReply, SendMailRequest, SendMailService, SendMailWithInlineAttachmentsRequest}
import at.energydash.config.Config
import at.energydash.mailer.ConfiguredMailer
import courier.{Envelope, Mailer, Multipart}

import java.nio.charset.Charset
import javax.mail.Session
import javax.mail.internet.{InternetAddress, MimeBodyPart}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class InlineAttachment(contentId: String, filename: String, mimeType: String, content: ByteString)
case class MailInlineMessage(from: String, to: String, cc: Option[String], subject: String, htmlBody: String, inlineContent: Seq[InlineAttachment], attachment: Option[MailAttachment])
case class MailContent(from: String, to: String, cc: Option[String], subject: String, content: Option[Multipart])

case class MailAttachment(filename: String, mimeType: String, content: ByteString)
case class AdminMail(from: String, to: String, subject: String, body: Option[String], attachment: Option[MailAttachment])

class SendMailServiceImpl(session: Session)(implicit val system: ActorSystem[_]) extends SendMailService {
  implicit val timeout: Timeout = 10.seconds
  implicit val sch: Scheduler = system.scheduler

  import system._

  /**
   * Send Mail with inline images/pdfs
   */
  override def sendMailWithInlineAttachment(in: SendMailWithInlineAttachmentsRequest): Future[SendMailReply] = {
    system.log.info(s"Send Inline Mail: To:${in.recipient} CC:${in.cc} - ${in.subject}")
    val from = Config.adminMailFrom
    val inlineMail = MailInlineMessage(
      from = from, to = in.recipient, in.cc, subject = in.subject,
      htmlBody = in.htmlBody,
      inlineContent = in.inlineContent
        .flatMap(a =>
          if (a.contentId.isEmpty) None
          else Some(InlineAttachment(a.contentId.get, a.filename, a.mimeType, ByteString(a.content.toByteArray)))),
      attachment = in.attachment match {
        case Some(a) => Some(MailAttachment(a.filename, a.mimeType, ByteString(a.content.toByteArray)))
        case None => None
      }
    )

    shippingInlineHtmlEmail(ConfiguredMailer.createMailerFromSession(session), inlineMail).transformWith {
      case Success(rejected) => Future(SendMailReply(200, Some("Email sent"), rejected))
      case Failure(exception) => {
        system.log.error(exception.toString)
        Future(SendMailReply(500, Some(exception.toString)))
      }
    }
  }

  override def sendMail(in: SendMailRequest): Future[SendMailReply] = {
    system.log.info(s"Send Mail: ${in.subject}")

    val subject = in.subject
    val to = in.recipient
    val from = Config.adminMailFrom

    val mailAttachment = in.attachment match {
      case Some(a) => Some(MailAttachment(a.filename, a.mimeType, ByteString(a.content.toByteArray)))
      case None => None
    }

    val adminMail = AdminMail(from, to, subject, in.body.map(b => b.toStringUtf8), mailAttachment)
    shippingEmail(ConfiguredMailer.createMailerFromSession(session), adminMail).transformWith {
      case Success(rejected) => Future(SendMailReply(200, Some("Email sent"), rejected))
      case Failure(exception) => {
        system.log.error(exception.toString)
        Future(SendMailReply(500, Some(exception.toString)))
      }
    }
  }

  private def shippingInlineHtmlEmail(mailer: Mailer, email: MailInlineMessage)(implicit ec: ExecutionContext): Future[Seq[String]] = {

    val mailContent = email.inlineContent.foldLeft(Multipart(subtype = "related").html(email.htmlBody))((a, b) => {
      val imagePart = new MimeBodyPart()
      imagePart.setContentID(s"<${b.contentId}>")
      imagePart.setDisposition("inline")
      imagePart.setContent(b.content.toArray, b.mimeType)
      a.add(imagePart)
    })

    executeMail(mailer, MailContent(email.from, email.to, email.cc, email.subject,
      Some(email.attachment.map(a => mailContent.attachBytes(a.content.toArray, a.filename, a.mimeType)).getOrElse(mailContent))))(ec)
  }

  private def shippingEmail(mailer: Mailer, email: AdminMail)(implicit ex: ExecutionContext): Future[Seq[String]] = {
    system.log.info(s"About to send Email ${email.from} to ${email.to}")

    val mailContent = email.attachment.foldLeft(email.body.foldLeft(Multipart())((m, b) => m.html(b, Charset.forName("UTF-8"))))((m, a) => {
      m.attachBytes(a.content.toArray, a.filename, a.mimeType)
    })

    executeMail(mailer, MailContent(email.from, email.to, None, email.subject, Some(mailContent)))(ex)
  }

  // Sends to all valid recipients and returns the rejected (invalid)
  // ones so the caller can report them in the SendMailReply — instead
  // of silently dropping them as before. No valid recipient at all is
  // a hard error rather than a mail without a "to".
  private def executeMail(mailer: Mailer, email: MailContent)(implicit ec: ExecutionContext): Future[Seq[String]] = {
    val (validTo, rejectedTo) = splitAddressList(email.to)
    val (validCc, rejectedCc) = email.cc.map(splitAddressList).getOrElse((Seq.empty[String], Seq.empty[String]))
    val rejected = rejectedTo ++ rejectedCc

    if (validTo.isEmpty) {
      Future.failed(new IllegalArgumentException(s"no valid recipient address (rejected: ${rejected.mkString(";")})"))
    } else {
      val withTo = validTo.foldLeft(Envelope.from(new InternetAddress(s"${email.from}")))((e, to) => e.to(new InternetAddress(to)))
      val withCc = validCc.foldLeft(withTo)((e, cc) => e.cc(new InternetAddress(cc)))
      val envelope = email.content.foldLeft(withCc.subject(email.subject))((e, m) => e.content(m))

      mailer(envelope)(ec).map(_ => rejected)
    }
  }

  // Shared address rule across the suite (backend, eda-xp, billing,
  // web): trimmed, ASCII local part, TLD of at least two letters — no
  // TLD allowlist. The previous closed list (aero|...|travel|[a-z][a-z])
  // silently dropped modern gTLDs like .energy or .online.
  private def isValidEmail(email: String): Boolean =
    """^(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$""".r.findFirstIn(email).isDefined

  // Splits a ';'-separated address list, strips outer unicode
  // whitespace (incl. NBSP) per part, drops empties and partitions
  // into (valid, rejected).
  private def splitAddressList(list: String): (Seq[String], Seq[String]) =
    list.split(";").toSeq.map(_.strip()).filter(_.nonEmpty).partition(isValidEmail)
}
