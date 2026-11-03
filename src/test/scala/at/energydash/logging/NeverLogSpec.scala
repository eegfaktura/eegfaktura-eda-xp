package at.energydash.logging

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/**
 * The never-log rule, enforced on the source (dev#45 L11, logging concept W-261008-ca2): reads the log calls under
 * `src/main/scala` (`logger.…`, `log.…`, `.setMessage(`, `.log(`) with [[NeverLogScan]]. It does not read
 * `.addKeyValue(` (the encoder writes only the fields of [[LogFields.Allowed]]) or `println`. Every value of a call is
 * a finding until the allowlist below names it for its file, with the reason why it holds no personal data and no
 * document; a false positive of a rule is fixed in the scanner instead. An entry without a use fails.
 */
class NeverLogSpec extends AnyWordSpec with Matchers {
  import NeverLogScan._

  private case class Allowed(file: String, term: String, reason: String)

  // metering point numbers may be logged (decision W-261008-ca2)
  private val Allowlist = Seq(
    Allowed("actors/AdminServer.scala", "address.getHostString", "bind address of this service"),
    Allowed("actors/AdminServer.scala", "address.getPort", "bind port of this service"),
    Allowed("actors/ConversationEntity.scala", "message.conversationId", "EDA conversation id"),
    Allowed("actors/ConversationEntity.scala", "notification.message.conversationId", "EDA conversation id"),
    Allowed("actors/MqttPublisher.scala", "LogText.errorText(msg)", "an error text of the aggregator, masked by LogText"),
    Allowed("actors/MqttPublisher.scala", "notification.map(_.message.conversationId).mkString(\", \")", "EDA conversation ids"),
    Allowed("actors/MqttPublisher.scala", "reason", "fixed texts with ids, no document"),
    Allowed("actors/MqttPublisher.scala", "tenantId", "tenant id, an RC number"),
    Allowed("actors/PrepareMessageActor.scala", "MessageHelper.formatSeqNumber(math.max(state.messageId, seqFloor))", "message counter, formatted"),
    Allowed("actors/PrepareMessageActor.scala", "seqFloor", "message counter floor from the configuration"),
    Allowed("actors/PrepareMessageActor.scala", "state.messageId", "message counter"),
    Allowed("actors/SupervisorActor.scala", "address.getHostString", "bind address of this service"),
    Allowed("actors/SupervisorActor.scala", "address.getPort", "bind port of this service"),
    Allowed("actors/SupervisorActor.scala", "m.getClass.getSimpleName", "a class name"),
    Allowed("actors/TenantMailActor.scala", "req.email.data.conversationId", "the EmailModel of the request: its EDA conversation id"),
    Allowed("actors/TenantMailActor.scala", "req.email.data.messageCode", "the EmailModel of the request: its EDA message code"),
    Allowed("actors/TenantMailWorker.scala", "data.conversationId", "EDA conversation id"),
    Allowed("actors/TenantMailWorker.scala", "data.messageCode", "EDA message code from a closed list"),
    Allowed("actors/TenantMailWorker.scala", "email.attachment.length", "the EmailModel: the size of its attachment"),
    Allowed("actors/TenantMailWorker.scala", "email.data.conversationId", "the EmailModel: its EDA conversation id"),
    Allowed("actors/TenantMailWorker.scala", "interval.toMillis", "a duration from the configuration"),
    Allowed("actors/TenantMailWorker.scala", "tenant.tenant", "tenant id, an RC number"),
    Allowed("actors/TenantProvider.scala", "Config.superviseType.getOrElse(\"All\")", "tenant type from the configuration"),
    Allowed("actors/TenantProvider.scala", "age.toSeconds", "a duration"),
    Allowed("actors/TenantProvider.scala", "buffer.capacity", "a count of messages"),
    Allowed("actors/TenantProvider.scala", "buffer.size", "a count of messages"),
    Allowed("actors/TenantProvider.scala", "cmd.message.messageCode", "EDA message code from a closed list"),
    Allowed("actors/TenantProvider.scala", "cmd.tenant", "tenant id, an RC number"),
    Allowed("actors/TenantProvider.scala", "msg.getClass.getSimpleName", "a class name"),
    Allowed("actors/TenantProvider.scala", "msg.tenant", "tenant id, an RC number"),
    Allowed("actors/TenantProvider.scala", "other.getClass.getSimpleName", "a class name"),
    Allowed("actors/TenantProvider.scala", "t.tenant", "tenant id, an RC number"),
    Allowed("actors/TenantProvider.scala", "tenant", "tenant id, an RC number"),
    Allowed("actors/TenantProvider.scala", "tenantActors.keys.mkString(\", \")", "tenant ids"),
    Allowed("actors/TenantProvider.scala", "tenantActors.size", "a count of tenants"),
    Allowed("actors/TenantProvider.scala", "tenantConfig.tenant", "tenant id, an RC number"),
    Allowed("actors/http/AccessLog.scala", "method", "HTTP method"),
    Allowed("actors/http/AccessLog.scala", "route.getOrElse(\"-\")", "route template, a closed list"),
    Allowed("actors/http/AccessLog.scala", "status", "HTTP status"),
    Allowed("actors/http/BodySizeLimit.scala", "declared.fold(\"none\")(b => s\"$b bytes\")", "a size in bytes"),
    Allowed("actors/http/BodySizeLimit.scala", "maxBytes", "limit from the configuration"),
    Allowed("actors/http/BodySizeLimit.scala", "what", "fixed name of a route"),
    Allowed("actors/http/Client.scala", "in.length", "a length in bytes"),
    Allowed("actors/http/HealthRoute.scala", "HealthRoute.describe(ex)", "class and message of a database error of SELECT 1, no query parameters"),
    Allowed("actors/http/HealthRoute.scala", "TooManyConnections", "a constant SQLSTATE"),
    Allowed("actors/http/HealthRoute.scala", "describe(ex)", "class and message of a database error of SELECT 1, no query parameters"),
    Allowed("actors/http/PontonRoute.scala", "b", "a size in bytes"),
    Allowed("actors/http/PontonRoute.scala", "d.data.length", "a length in bytes"),
    Allowed("actors/http/PontonRoute.scala", "details", "logSize: message code and conversation id, or a fixed text, at both callers"),
    Allowed("actors/http/PontonRoute.scala", "forwardTargets.map { case (rc, uri) => s\"$rc -> $uri\" }.mkString(\", \")", "RC numbers and URIs from the configuration"),
    Allowed("actors/http/PontonRoute.scala", "forwardTimeout", "duration from the configuration"),
    Allowed("actors/http/PontonRoute.scala", "maxBodyBytes", "limit from the configuration"),
    Allowed("actors/http/PontonRoute.scala", "ms", "a duration"),
    Allowed("actors/http/PontonRoute.scala", "notification.message.errorMessage.getOrElse(\"\")", "error text eda-xp sets itself in XmlParseHandler and notificationFor"),
    Allowed("actors/http/PontonRoute.scala", "rc", "RC number of a community"),
    Allowed("actors/http/PontonRoute.scala", "readTimeout", "duration from the configuration"),
    Allowed("actors/http/PontonRoute.scala", "requestTimeout.get", "duration from the configuration"),
    Allowed("actors/http/PontonRoute.scala", "response.head.length", "a length in characters"),
    Allowed("actors/http/PontonRoute.scala", "size.fold(\"size unknown\")(b => s\"$b bytes\")", "a size in bytes"),
    Allowed("actors/http/PontonRoute.scala", "status", "HTTP status"),
    Allowed("actors/http/PontonRoute.scala", "target", "forward target, a URI from the configuration"),
    Allowed("actors/http/PontonRoute.scala", "x.conversationId", "EDA conversation id"),
    Allowed("actors/http/PontonRoute.scala", "x.messageCode", "EDA message code from a closed list"),
    Allowed("actors/http/PontonRoute.scala", "x.receiver", "market partner id of an inbound message (RC or EC number)"),
    Allowed("domain/XmlParseHandler.scala", "faultCode(x)", "a QName by its pattern, never the fault text"),
    Allowed("domain/eda/EdaXMLMessage.scala", "msg", "EdaMessage.fallbackVersion: version label, message code and conversation id"),
    Allowed("mailer/EmailService.scala", "email.data.conversationId", "the EmailModel: its EDA conversation id"),
    Allowed("mailer/EmailService.scala", "email.data.messageCode", "the EmailModel: its EDA message code"),
    Allowed("mailer/Fetcher.scala", "ctx.tenant", "tenant id, an RC number"),
    Allowed("mailer/Fetcher.scala", "e.getClass.getName", "a class name"),
    Allowed("mailer/Fetcher.scala", "id", "Message-ID header of a mail"),
    Allowed("mailer/Fetcher.scala", "loggableId(messageId)", "a value of the shape of an id, or a fixed text"),
    Allowed("mailer/Fetcher.scala", "m.getMessageNumber", "number of a mail in the mailbox"),
    Allowed("mailer/Fetcher.scala", "messages.length", "a count of mails"),
    Allowed("mailer/Fetcher.scala", "protocol", "EDA process code; `[A-Za-z_-]*` by the pattern that yields it"),
    Allowed("mailer/Fetcher.scala", "version", "EDA schema version; digits and a dot by the pattern that yields it"),
    Allowed("mqtt/MqttSystem.scala", "command.command", "name of an MQTT command"),
    Allowed("mqtt/MqttSystem.scala", "ev.ev.message.conversationId", "EDA conversation id"),
    Allowed("mqtt/MqttSystem.scala", "ev.message.conversationId", "EDA conversation id"),
    Allowed("mqtt/MqttSystem.scala", "reason", "fixed texts; the text of a failed publish stream names no document"),
    Allowed("mqtt/MqttSystem.scala", "x.message.qos.get.value", "MQTT quality of service"),
    Allowed("mqtt/MqttSystem.scala", "x.message.topic", "MQTT topic: tenant and process"),
    Allowed("service/FileService.scala", "info.processName", "form part name of /admin/upload, an EDA process code, operator input and no message content"),
    Allowed("service/FileService.scala", "protocol", "EDA process code; `[A-Za-z_-]*` by the pattern that yields it"),
    Allowed("service/FileService.scala", "version", "EDA schema version; digits and a dot by the pattern that yields it"),
    Allowed("service/SendMailServiceImpl.scala", "NoValidRecipient", "a constant text, without the recipients"),
    Allowed("service/SendMailServiceImpl.scala", "logText(exception)", "toString without addresses"),
    Allowed("stream/MqttRequestStream.scala", "msg.topic", "MQTT topic: tenant and process"),
  )

  private lazy val sources: Seq[(String, String)] = {
    val root = Paths.get("src/main/scala/at/energydash")
    Files.walk(root).iterator().asScala.filter(_.toString.endsWith(".scala")).toList
      .map((p: Path) => root.relativize(p).toString.replace('\\', '/') -> Files.readString(p))
  }
  private lazy val calls: Seq[Call] = sources.flatMap { case (file, text) => logCalls(file, text) }
  private lazy val hits: Seq[Hit] = calls.flatMap(findings)

  private def allowed(hit: Hit): Boolean = Allowlist.exists(a => a.file == hit.file && a.term == hit.term)

  private def findingsOf(code: String): Seq[Hit] = logCalls("T.scala", code).flatMap(findings)
  private def rulesOf(code: String): Seq[String] = findingsOf(code).map(_.rule)

  "The log calls of the service" should {
    "be found: the scan reads real code" in {
      sources.size should be > 50
      calls.size should be > 100
    }
    "hold nothing that may not be logged" in {
      val open = hits.filterNot(allowed)
      withClue(open.mkString("\n", "\n", "\n"))(open shouldBe empty)
    }
    "have a reason for every allowlist entry and no entry without a use" in {
      Allowlist.filter(_.reason.trim.length < 10) shouldBe empty
      Allowlist.filterNot(a => hits.exists(h => h.file == a.file && h.term == a.term)) shouldBe empty
    }
  }

  "The scan" should {
    "find every value, so that a new one fails until it is allowed for its file" in {
      findingsOf("""logger.info(s"got $message")""").map(_.term) shouldBe Seq("message")
      findingsOf("""log.warn(s"got ${message}")""") should have size 1
      findingsOf("""context.log.info(s"got ${req.member}")""").map(h => h.term -> h.rule) shouldBe Seq("req.member" -> NotKnownSafe)
      findingsOf("""logger.error(text2)""") should have size 1
      findingsOf("""logger.info("x {}", member)""") should have size 1
      findingsOf("""ctx.setMessage(s"$member")""") should have size 1
      findingsOf("""logger.info("Send Email (no value here)")""") shouldBe empty
    }
    "refuse toString and an exception in the text" in {
      rulesOf("""logger.error(e.toString)""") shouldBe Seq(ToStringCall)
      rulesOf("""logger.info(s"${message.conversationId.toString}")""") shouldBe Seq(ToStringCall)
      rulesOf("""logger.error(s"failed: $e")""") shouldBe Seq(ExceptionInText)
      rulesOf("""logger.error(s"failed: $e", e)""") shouldBe Seq(ExceptionInText)
    }
    // dev#45 Q1: the encoder masks the text of an exception only on the attached exception
    "refuse the text of an exception in the message, also with the exception attached" in {
      Seq("""logger.error(e.getMessage)""", """logger.warn(s"failed: ${e.getMessage}")""",
        """logger.warn(s"failed: ${e.getMessage()}")""", """logger.error(s"failed: ${e.getMessage}", other)""",
        """logger.error(s"failed: ${e.getMessage}", e)""", """logger.error(e.getMessage, e)""",
        """logger.error(s"failed: ${ex.getLocalizedMessage}", ex)""", """logger.error(s"failed: ${LogText.errorText(ex)}", ex)""")
        .foreach(code => withClue(code)(rulesOf(code) should contain(ExceptionInMessage)))
      findingsOf("""logger.error("failed", e)""") shouldBe empty
      rulesOf("""logger.info(s"mail ${m.getMessageNumber}")""") shouldBe Seq(NotKnownSafe)
    }
    // dev#45 Q7: the word list of all services, as whole words, on every segment of a dotted value
    "refuse a never-log word in any identifier of a value" in {
      Seq("firstname", "lastName", "email", "toEmail", "iban", "password", "token", "soapBody", "api_key", "apiKey",
        "accountHolder", "sentTo", "userId", "getSubject", "jwt", "name1", "mailAddress", "bearerToken").foreach { name =>
        withClue(name)(rulesOf(s"""logger.info(s"x $${member.$name}")""").mkString should include(PersonalWord))
      }
      rulesOf("""logger.info(s"x ${email.data.conversationId}")""") shouldBe Seq(s"$PersonalWord: email")
      rulesOf("""logger.info(s"x ${names.map(_.lastname).mkString}")""") shouldBe Seq(s"$PersonalWord: lastname")
    }
    "match whole words only, and not read string literals" in {
      Seq("substring", "tokenizer", "subjects", "nameless", "owners").foreach { name =>
        withClue(name)(rulesOf(s"""logger.info(s"x $${a.$name}")""") shouldBe Seq(NotKnownSafe))
      }
      findingsOf("""logger.info("the password and the email are not logged")""") shouldBe empty
      rulesOf("""logger.info(s"x ${a.getOrElse("token")}")""") shouldBe Seq(NotKnownSafe)
    }
    "see a call over several lines and a parenthesis in the text, and not a comment or a plain string" in {
      findingsOf("logger.error(\n  s\"a ) b $member\",\n  e)") should have size 1
      findingsOf("// logger.info(s\"$member\")\n/* logger.info(s\"$member\") */") shouldBe empty
      findingsOf("""val a = "logger.info(s$member)"""") shouldBe empty
      findingsOf("""logger.info("not interpolated $member")""") shouldBe empty
    }
    "report file and line" in {
      logCalls("a/B.scala", "\n\nlogger.info(s\"$member\")").flatMap(findings).head.toString should startWith("a/B.scala:3 ")
    }
  }
}
