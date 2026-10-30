package at.energydash.interfaces

import at.energydash.actors.AdminServer
import at.energydash.admin.mail.{Attachment, SendMailRequest, SendMailWithInlineAttachmentsRequest}
import at.energydash.service.SendMailServiceImpl
import at.energydash.testsupport.LogCapture
import com.google.protobuf.ByteString
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.jvnet.mock_javamail.Mailbox
import org.scalatest.wordspec.AnyWordSpecLike

import javax.mail.internet.MimeMultipart
import scala.concurrent.Await
import scala.concurrent.duration._

/** gRPC `SendMailService` (caller: backend, activation mails) on the admin mail session; mock-javamail. */
class SendMailSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  private val service = new SendMailServiceImpl(AdminServer.mailSession)(system)

  "SendMailService" should {
    "send a mail with body and attachment" in {
      Mailbox.clearAll()
      val reply = Await.result(service.sendMail(SendMailRequest(sender = "x", recipient = "member@email.com",
        subject = "Willkommen", body = Some(ByteString.copyFromUtf8("<p>Hallo</p>")),
        attachment = Some(Attachment(filename = "info.pdf", mimeType = "application/pdf", content = ByteString.copyFromUtf8("%PDF-1.4"))))), 15.seconds)
      reply.status shouldBe 200
      val inbox = Mailbox.get("member@email.com")
      inbox should have size 1
      inbox.get(0).getSubject shouldBe "Willkommen"
      inbox.get(0).getContent.asInstanceOf[MimeMultipart].getCount should be >= 2
    }
    "send an HTML mail with an inline image and an attachment" in {
      Mailbox.clearAll()
      val reply = Await.result(service.sendMailWithInlineAttachment(SendMailWithInlineAttachmentsRequest(
        sender = "x", recipient = "member@email.com", subject = "Aktivierung", htmlBody = "<img src=\"cid:logo\"/>",
        inlineContent = Seq(Attachment(contentId = Some("logo"), filename = "logo.png", mimeType = "image/png", content = ByteString.copyFrom(Array[Byte](1, 2, 3)))),
        cc = Some("cc@email.com"),
        attachment = Some(Attachment(filename = "info.pdf", mimeType = "application/pdf", content = ByteString.copyFromUtf8("%PDF-1.4"))))), 15.seconds)
      reply.status shouldBe 200
      Mailbox.get("member@email.com").get(0).getSubject shouldBe "Aktivierung"
      Mailbox.get("cc@email.com") should have size 1
    }
    // no e-mail address in the log: neither the recipients of a sent mail nor the address in a send error
    "log no e-mail address" in {
      Mailbox.clearAll()
      val (_, events) = LogCapture("ROOT") {
        Await.result(service.sendMail(SendMailRequest(sender = "x", recipient = "member@email.com",
          subject = "Willkommen", body = Some(ByteString.copyFromUtf8("<p>Hallo</p>")))), 15.seconds).status shouldBe 200
        Await.result(service.sendMailWithInlineAttachment(SendMailWithInlineAttachmentsRequest(
          sender = "x", recipient = "member@email.com", subject = "Aktivierung", htmlBody = "<p/>", cc = Some("cc@email.com"))), 15.seconds)
          .status shouldBe 200
        Await.result(service.sendMail(SendMailRequest(sender = "x", recipient = "a b@email.com",
          subject = "x", body = Some(ByteString.copyFromUtf8("x")))), 15.seconds)
      }
      val logged = LogCapture.messages(events)
      logged should not be empty
      logged.exists(_.contains("@email.com")) shouldBe false
    }
    "answer an error for an invalid recipient (shape pinned)" in {
      val reply = Await.result(service.sendMail(SendMailRequest(sender = "x", recipient = "not an address",
        subject = "x", body = Some(ByteString.copyFromUtf8("x")))), 15.seconds)
      (reply.status != 200 || reply.rejectedRecipients.nonEmpty) shouldBe true
    }
  }
}
