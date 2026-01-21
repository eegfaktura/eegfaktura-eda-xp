package at.energydash.service

import akka.actor.typed.{ActorSystem, Scheduler}
import akka.util.{ByteString, Timeout}
import at.energydash.admin.mail.{SendMailReply, SendMailRequest, SendMailService, SendMailWithInlineAttachmentsRequest}
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
    val from = "no-reply@eegfaktura.at"
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
      case Success(_) => Future(SendMailReply(200, Some("Email sent")))
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
    val from = "no-reply@eegfaktura.at"

    val mailAttachment = in.attachment match {
      case Some(a) => Some(MailAttachment(a.filename, a.mimeType, ByteString(a.content.toByteArray)))
      case None => None
    }

    val adminMail = AdminMail(from, to, subject, in.body.map(b => b.toStringUtf8), mailAttachment)
    shippingEmail(ConfiguredMailer.createMailerFromSession(session), adminMail).transformWith {
      case Success(_) => Future(SendMailReply(200, Some("Email sent")))
      case Failure(exception) => {
        system.log.error(exception.toString)
        Future(SendMailReply(500, Some(exception.toString)))
      }
    }
  }

  private def shippingInlineHtmlEmail(mailer: Mailer, email: MailInlineMessage)(implicit ec: ExecutionContext): Future[Unit] = {

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

  private def shippingEmail(mailer: Mailer, email: AdminMail)(implicit ex: ExecutionContext): Future[Unit] = {
    system.log.info(s"About to send Email ${email.from} to ${email.to}")

    val mailContent = email.attachment.foldLeft(email.body.foldLeft(Multipart())((m, b) => m.html(b, Charset.forName("UTF-8"))))((m, a) => {
      m.attachBytes(a.content.toArray, a.filename, a.mimeType)
    })

    executeMail(mailer, MailContent(email.from, email.to, None, email.subject, Some(mailContent)))(ex)
  }

  private def executeMail(mailer: Mailer, email: MailContent)(implicit ec: ExecutionContext): Future[Unit] = {
    val envelope = email.content.foldLeft(email.to.split(";").foldLeft(
          Envelope.from(new InternetAddress(s"${email.from}")))((e, to) => buildInetAddress(s"${to.trim}") match {
            case Some(i) => e.to(i)
            case None => e
          })
      .subject(email.subject))((e, m) => e.content(m))

    mailer(email.cc match {
      case Some(cc) if isValidEmail(cc) => envelope.cc(new InternetAddress(cc))
      case _ => envelope
    })(ec)
  }

  private def isValidEmail(email: String): Boolean = if ("""^[-a-z0-9!#$%&'*+/=?^_`{|}~]+(\.[-a-z0-9!#$%&'*+/=?^_`{|}~]+)*@([a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?\.)*(aero|arpa|asia|biz|cat|com|coop|edu|gov|info|int|jobs|mil|mobi|museum|name|net|org|pro|tel|travel|[a-z][a-z])$""".r.findFirstIn(email).isEmpty) false else true

  private def buildInetAddress(a: String) = {
    if (isValidEmail(a)) Some(new InternetAddress(a))
    else None
  }
}
