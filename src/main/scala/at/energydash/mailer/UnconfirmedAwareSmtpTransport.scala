package at.energydash.mailer

import com.sun.mail.smtp.SMTPTransport

import java.net.SocketTimeoutException
import javax.mail.{MessagingException, Session, URLName}

/** The message was sent completely ("." included), but the relay's reply to it did not arrive in time:
 *  the relay has most likely accepted the mail, so it must not count as "not sent" (resend → duplicate). */
class UnconfirmedDeliveryException(next: Exception)
  extends MessagingException("no reply from the SMTP server after the message was sent", next)

/** SMTP transport of the admin mail session: marks a timeout while waiting for the reply to the end of the
 *  message data as [[UnconfirmedDeliveryException]]. Everything else behaves like [[SMTPTransport]]. */
class UnconfirmedAwareSmtpTransport(session: Session, urlname: URLName) extends SMTPTransport(session, urlname) {

  // Called after the message body was written: sends "." and reads the relay's reply.
  override protected def finishData(): Unit =
    try super.finishData()
    catch {
      case mex: MessagingException if causes(mex).exists(_.isInstanceOf[SocketTimeoutException]) =>
        // Keep the IOException as next exception: SMTPTransport then closes the connection instead of
        // sending QUIT to a relay that does not answer.
        throw new UnconfirmedDeliveryException(Option(mex.getNextException).getOrElse(mex))
    }

  private def causes(e: Throwable): Iterator[Throwable] = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(10)
}
