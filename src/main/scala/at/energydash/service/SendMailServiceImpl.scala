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

    val related = email.inlineContent.foldLeft(Multipart(subtype = "related").html(email.htmlBody, Utf8))((a, b) => {
      val imagePart = new MimeBodyPart()
      imagePart.setContentID(s"<${b.contentId}>")
      imagePart.setDisposition("inline")
      imagePart.setContent(b.content.toArray, b.mimeType)
      a.add(imagePart)
    })
    // multipart/alternative { text/plain, multipart/related { html, inline images } }
    val alternative = Multipart(subtype = "alternative").text(htmlToPlainText(email.htmlBody), Utf8).add(asBodyPart(related))
    val mailContent = email.attachment
      .map(a => Multipart().add(asBodyPart(alternative)).attachBytes(a.content.toArray, a.filename, a.mimeType))
      .getOrElse(alternative)

    executeMail(mailer, MailContent(email.from, email.to, email.cc, email.subject, Some(mailContent)))(ec)
  }

  private def shippingEmail(mailer: Mailer, email: AdminMail)(implicit ex: ExecutionContext): Future[Seq[String]] = {
    system.log.info(s"About to send Email ${email.from} to ${email.to}")

    val mailContent = (email.body, email.attachment) match {
      case (Some(html), Some(a)) =>
        val alt = Multipart(subtype = "alternative").text(htmlToPlainText(html), Utf8).html(html, Utf8)
        Multipart().add(asBodyPart(alt)).attachBytes(a.content.toArray, a.filename, a.mimeType)
      case (Some(html), None) =>
        Multipart(subtype = "alternative").text(htmlToPlainText(html), Utf8).html(html, Utf8)
      case (None, Some(a)) =>
        Multipart().attachBytes(a.content.toArray, a.filename, a.mimeType)
      case (None, None) =>
        Multipart()
    }

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
        .headers("Auto-Submitted" -> "auto-generated")

      mailer(envelope)(ec).map(_ => rejected)
    }
  }

  private val Utf8: Charset = Charset.forName("UTF-8")

  // Wrap a Courier Multipart as a MimeBodyPart so it can be nested inside
  // another multipart (a multipart/related inside a multipart/alternative).
  private def asBodyPart(mp: Multipart): MimeBodyPart = {
    val part = new MimeBodyPart()
    part.setContent(mp.parts)
    part
  }

  // Derives a plain-text alternative from the rendered HTML. Good enough for
  // readability next to the HTML part — not a full HTML renderer.
  private def htmlToPlainText(html: String): String = {
    if (html == null) "" else {
      val stripped = html
        .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
        .replaceAll("(?i)<br\\s*/?>", "\n")
        .replaceAll("(?i)</(p|div|tr|li|h[1-6]|table)>", "\n")
        .replaceAll("(?s)<[^>]+>", "")
      val decoded = stripped
        .replace("&nbsp;", " ")
        .replace("&auml;", "ä").replace("&ouml;", "ö").replace("&uuml;", "ü")
        .replace("&Auml;", "Ä").replace("&Ouml;", "Ö").replace("&Uuml;", "Ü")
        .replace("&szlig;", "ß")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
      decoded.replaceAll("[ \\t]+", " ").replaceAll("(?m)^[ \\t]+", "").replaceAll("\\n{3,}", "\n\n").trim
    }
  }

  // Shared address rule across the suite (backend, eda-xp, billing,
  // web): trimmed, ASCII local part, TLD of at least two letters — no
  // TLD allowlist. The previous closed list (aero|...|travel|[a-z][a-z])
  // silently dropped modern gTLDs like .energy or .online.
  private def isValidEmail(email: String): Boolean =
    """^(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$""".r.findFirstIn(email).isDefined

  // Outer whitespace per address part. String#strip alone is NOT
  // enough: Character.isWhitespace excludes the non-breaking spaces
  // U+00A0 / U+202F / U+2007 (typical Excel & copy-paste artifacts),
  // which the Go (unicode.IsSpace) and JS (trim) sides of the shared
  // rule do heal — so strip them here explicitly.
  private val OuterWhitespace = "^[\\s\u00A0\u202F\u2007]+|[\\s\u00A0\u202F\u2007]+$".r

  // Splits a ';'-separated address list, strips outer whitespace
  // (incl. non-breaking spaces) per part, drops empties and
  // partitions into (valid, rejected).
  private def splitAddressList(list: String): (Seq[String], Seq[String]) =
    list.split(";").toSeq.map(OuterWhitespace.replaceAllIn(_, "")).filter(_.nonEmpty).partition(isValidEmail)
}
