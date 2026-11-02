package at.energydash.logging

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/**
 * The never-log rule, enforced on the source (dev#45 L11, logging concept W-261008-ca2): reads every log call under
 * `src/main/scala` and fails on a value that is not known to be safe, on `getMessage` without the exception and on
 * personal names. The scan is a positive list: a new `$value` fails until it is added to [[NeverLogScan.Allowed]]
 * with the reason why it holds no personal data and no document. That list is the only way past the scan.
 */
class NeverLogSpec extends AnyWordSpec with Matchers {
  import NeverLogScan._

  private lazy val sources: Seq[(String, String)] = {
    val root = Paths.get("src/main/scala")
    Files.walk(root).iterator().asScala.filter(_.toString.endsWith(".scala")).toList
      .map((p: Path) => root.relativize(p).toString.replace('\\', '/') -> Files.readString(p))
  }
  private lazy val calls: Seq[Call] = sources.flatMap { case (file, text) => logCalls(file, text) }

  private def findingsOf(code: String): Seq[String] = logCalls("T.scala", code).flatMap(findings)

  "The log calls of the service" should {
    "be found: the scan reads real code" in {
      sources.size should be > 50
      calls.size should be > 100
    }
    "hold nothing that may not be logged" in {
      val all = calls.flatMap(findings)
      withClue(all.mkString("\n", "\n", "\n"))(all shouldBe empty)
    }
    "have a reason for every allowlist entry and no entry without a use" in {
      Allowed.filter(_._2.trim.length < 10).keys shouldBe empty
      val used = calls.flatMap(_.values).map(_._1).flatMap(allowedEntry).toSet
      (Allowed.keySet -- used) shouldBe empty
    }
  }

  "The scan" should {
    "refuse a value that is not on the positive list" in {
      findingsOf("""logger.info(s"got $message")""") should have size 1
      findingsOf("""log.warn(s"got ${message}")""") should have size 1
      findingsOf("""context.log.info(s"got ${req.member}")""") should have size 1
      findingsOf("""logger.error(text2)""") should have size 1
      findingsOf("""logger.info("x {}", member)""") should have size 1
      findingsOf("""ctx.setMessage(s"$member")""") should have size 1
    }
    "accept a value on the positive list and plain text" in {
      findingsOf("""logger.info(s"conversation ${message.conversationId}")""") shouldBe empty
      findingsOf("""logger.info("Send Email (no value here)")""") shouldBe empty
    }
    "refuse toString and an exception in the text" in {
      findingsOf("""logger.error(e.toString)""").mkString should include("toString")
      findingsOf("""logger.info(s"${message.conversationId.toString}")""").mkString should include("toString")
      findingsOf("""logger.error(s"failed: $e")""") should have size 1
      findingsOf("""logger.error(s"failed: $e", e)""") should have size 1
    }
    "refuse getMessage without the exception, and accept it with" in {
      findingsOf("""logger.error(e.getMessage)""").mkString should include("getMessage")
      findingsOf("""logger.warn(s"failed: ${e.getMessage}")""").mkString should include("getMessage")
      findingsOf("""logger.warn(s"failed: ${e.getMessage()}")""").mkString should include("getMessage")
      findingsOf("""logger.error(s"failed: ${e.getMessage}", other)""").mkString should include("getMessage")
      findingsOf("""logger.error(s"failed: ${e.getMessage}", e)""") shouldBe empty
      findingsOf("""logger.error(e.getMessage, e)""") shouldBe empty
    }
    "refuse personal names in a value and as a label in the text" in {
      Seq("firstname", "lastName", "email", "toEmail", "iban", "password", "token", "soapBody").foreach { name =>
        withClue(name)(findingsOf(s"""logger.info(s"x $${member.$name}")""").mkString should include("personal"))
      }
      findingsOf("""logger.info(s"password=${message.conversationId}")""").mkString should include("personal")
      findingsOf("""logger.info(s"x ${names.map(_.lastname).mkString}")""").mkString should include("personal")
    }
    "see a call over several lines and a parenthesis in the text, and not a comment or a plain string" in {
      findingsOf("logger.error(\n  s\"a ) b $member\",\n  e)") should have size 1
      findingsOf("// logger.info(s\"$member\")\n/* logger.info(s\"$member\") */") shouldBe empty
      findingsOf("""val a = "logger.info(s$member)"""") shouldBe empty
      findingsOf("""logger.info("not interpolated $member")""") shouldBe empty
    }
    "report file and line" in {
      logCalls("a/B.scala", "\n\nlogger.info(s\"$member\")").flatMap(findings).head should startWith("a/B.scala:3 ")
    }
  }
}

object NeverLogScan {
  /** One log call: its interpolated and argument values, the literal text, and whether an exception comes last. */
  final case class Call(file: String, line: Int, values: Seq[(String, Boolean)], literals: Seq[String], cause: Boolean)

  /**
   * Values a log line may hold: a dotted path matches by its end (`message.conversationId` by `conversationId`), any
   * other expression by its exact text. Key -> why it is safe.
   */
  val Allowed: Map[String, String] = Map(
    // ids (metering point numbers may be logged too, decision W-261008-ca2)
    "conversationId" -> "EDA conversation id",
    "messageId" -> "EDA or Ponton message id",
    "id" -> "Message-ID header of a mail",
    "rc" -> "RC number of a community",
    "tenant" -> "tenant id, an RC number",
    "tenantId" -> "tenant id, an RC number",
    "receiver" -> "market partner id of an inbound message (RC or EC number)",
    "messageCode" -> "EDA message code from a closed list",
    "protocol" -> "EDA process code; `[A-Za-z_-]*` by the pattern that yields it",
    "version" -> "EDA schema version; digits and a dot by the pattern that yields it",
    "processName" -> "form part name of /admin/upload, an EDA process code, operator input and no message content",
    "command" -> "name of an MQTT command",
    "topic" -> "MQTT topic: tenant and process",
    "qos.get.value" -> "MQTT quality of service",
    "state.messageId" -> "message counter",
    "seqFloor" -> "message counter floor from the configuration",
    "MessageHelper.formatSeqNumber(math.max(state.messageId, seqFloor))" -> "message counter, formatted",
    // technical values
    "getClass.getName" -> "a class name",
    "getClass.getSimpleName" -> "a class name",
    "address.getHostString" -> "bind address of this service",
    "address.getPort" -> "bind port of this service",
    "target" -> "forward target, a URI from the configuration",
    "forwardTargets.map { case (rc, uri) => s\"$rc -> $uri\" }.mkString(\", \")" -> "RC numbers and URIs from the configuration",
    "tenantActors.keys.mkString(\", \")" -> "tenant ids",
    "Config.superviseType.getOrElse(\"All\")" -> "tenant type from the configuration",
    "status" -> "HTTP status",
    "ms" -> "a duration",
    "b" -> "a size in bytes",
    "method" -> "HTTP method",
    "route.getOrElse(\"-\")" -> "route template, a closed list",
    "what" -> "fixed name of a route",
    "length" -> "a length in characters or bytes",
    "size" -> "a count of items",
    "capacity" -> "a count of items",
    "toMillis" -> "a duration",
    "toSeconds" -> "a duration",
    "maxBytes" -> "limit from the configuration",
    "maxBodyBytes" -> "limit from the configuration",
    "readTimeout" -> "duration from the configuration",
    "forwardTimeout" -> "duration from the configuration",
    "requestTimeout.get" -> "duration from the configuration",
    "TooManyConnections" -> "a constant SQLSTATE",
    "NoValidRecipient" -> "a constant text, without the recipients",
    "declared.fold(\"none\")(b => s\"$b bytes\")" -> "a size in bytes",
    "size.fold(\"size unknown\")(b => s\"$b bytes\")" -> "a size in bytes",
    "serviceVersion(sys.env)" -> "the version of the image",
    "level(Logger.ROOT_LOGGER_NAME)" -> "a log level",
    "level(\"slick\")" -> "a log level",
    // texts that are built from the above or masked
    "details" -> "logSize: message code and conversation id, or a fixed text, at both callers",
    "reason" -> "fixed texts with ids; MqttPublisher's and MqttSystem's reasons name no document",
    "text" -> "PontonRoute: fixed text with LogText.errorText",
    "msg" -> "EdaMessage.fallbackVersion: version label, message code and conversation id",
    "LogText.errorText(ex)" -> "masked by LogText",
    "LogText.errorText(msg)" -> "masked by LogText",
    "logText(exception)" -> "SendMailServiceImpl: toString without addresses",
    "faultCode(x)" -> "XmlParseHandler: a QName by its pattern, never the fault text",
    "describe(ex)" -> "HealthRoute: class and message of a database error of SELECT 1, no query parameters",
    "HealthRoute.describe(ex)" -> "HealthRoute: class and message of a database error of SELECT 1, no query parameters",
    "notification.map(_.message.conversationId).mkString(\", \")" -> "conversation ids",
    "notification.message.errorMessage.getOrElse(\"\")" -> "PontonRoute: error text eda-xp sets itself in XmlParseHandler and notificationFor",
  )

  private val Personal = "(?i)first_?name|last_?name|e_?mail|iban|password|passwd|pwd|token|soap_?body".r
  private val PersonalLabel = ("(?i)(" + Personal.regex.stripPrefix("(?i)") + """)\s*[=:]\s*$""").r
  private val Throwables = Set("e", "ex", "err", "exception", "f", "me")
  private val SimplePath = """[A-Za-z_][\w.]*(\(\))?""".r
  private val Start = """(?:\b(?:logger|log)\.(?:trace|debug|info|warn|error)|\.setMessage|\.log)\(""".r

  /** The key of [[Allowed]] that covers `value`, if any. */
  def allowedEntry(value: String): Option[String] = {
    val path = value.stripSuffix("()")
    Allowed.keys.find(k => k == value || (SimplePath.matches(value) && (path == k || path.endsWith("." + k))))
  }

  def findings(call: Call): Seq[String] = {
    val where = s"${call.file}:${call.line}"
    val getMessage = call.values.exists(_._1.contains("getMessage"))
    val rules = Seq(
      Option.when(getMessage && !call.cause)("getMessage without the exception"),
      call.literals.find(l => PersonalLabel.findFirstIn(l).isDefined).map(l => s"personal name as a label: '$l'")) ++
      call.values.map { case (value, interpolated) => check(value, interpolated, call.cause) }
    rules.flatten.map(rule => s"$where $rule")
  }

  private def check(value: String, interpolated: Boolean, cause: Boolean): Option[String] = {
    val code = value.replaceAll("\"[^\"]*\"", "\"\"")
    // of a dotted path only the end is logged: `email.data.conversationId` is the conversation id
    val logged = if (SimplePath.matches(value)) value.stripSuffix("()").split('.').last else code
    if (Personal.findFirstIn(logged).isDefined) Some(s"personal name: $value")
    else if (code.contains("toString")) Some(s"toString: $value")
    else if (code.contains("getMessage")) None // with the exception (above), masked by the encoder
    else if (allowedEntry(value).isDefined) None
    else if (interpolated && Throwables(value)) Some(s"exception in the text instead of as the last argument: $value")
    else Some(s"value not on the positive list: $value")
  }

  /** The log calls in `text`, comments and strings outside of a call are skipped. */
  def logCalls(file: String, text: String): Seq[Call] = {
    val code = withoutCommentsAndStrings(text)
    Start.findAllMatchIn(code).toList.flatMap { m =>
      val call = new CallReader(text, m.end - 1).read()
      Option.when(call.nonEmpty)(Call(file, text.take(m.start).count(_ == '\n') + 1, call.values, call.literals, call.cause))
    }
  }

  /** `text` of the same length, with comments and string contents blanked, so that offsets stay valid. */
  private def withoutCommentsAndStrings(text: String): String = {
    val out = new StringBuilder(text)
    def blank(from: Int, to: Int): Unit = (from until math.min(to, text.length)).foreach(i => if (text(i) != '\n') out(i) = ' ')
    var i = 0
    while (i < text.length) {
      if (text.startsWith("//", i)) { val end = text.indexOf('\n', i); val stop = if (end < 0) text.length else end; blank(i, stop); i = stop }
      else if (text.startsWith("/*", i)) { val end = text.indexOf("*/", i); val stop = if (end < 0) text.length else end + 2; blank(i, stop); i = stop }
      else if (text.startsWith("'\"'", i)) { blank(i, i + 3); i += 3 }
      else if (text(i) == '"') { val stop = stringEnd(text, i); blank(i + 1, stop - 1); i = stop }
      else i += 1
    }
    out.toString
  }

  /** The offset after the string starting at `start`; an escaped quote and `${…}` with strings in it stay inside. */
  private def stringEnd(text: String, start: Int): Int = {
    val triple = text.startsWith("\"\"\"", start)
    val quote = if (triple) "\"\"\"" else "\""
    var i = start + quote.length
    while (i < text.length && !text.startsWith(quote, i)) {
      if (!triple && text(i) == '\\') i += 2
      else if (text.startsWith("${", i)) i = blockEnd(text, i + 1)
      else i += 1
    }
    i + quote.length
  }

  /** The offset after the `}` closing the `{` at `open`. */
  private def blockEnd(text: String, open: Int): Int = {
    var depth = 0
    var i = open
    while (i < text.length) {
      text(i) match {
        case '{' => depth += 1; i += 1
        case '}' => depth -= 1; i += 1; if (depth == 0) return i
        case '"' => i = stringEnd(text, i)
        case _ => i += 1
      }
    }
    i
  }

  /** Reads the arguments of the call whose `(` is at `open`. */
  private final class CallReader(text: String, open: Int) {
    private val values = Seq.newBuilder[(String, Boolean)]
    private val literals = Seq.newBuilder[String]

    final case class Read(values: Seq[(String, Boolean)], literals: Seq[String], cause: Boolean) {
      def nonEmpty: Boolean = values.nonEmpty || literals.nonEmpty
    }

    def read(): Read = {
      val args = Seq.newBuilder[String]
      val current = new StringBuilder
      var depth = 0
      var i = open
      var done = false
      while (!done && i < text.length) {
        text(i) match {
          case '"' =>
            val interpolated = i > 0 && "sfr".contains(text(i - 1)) && (i < 2 || !text(i - 2).isLetterOrDigit)
            val stop = stringEnd(text, i)
            string(i, stop, interpolated)
            current.append("\"\"")
            i = stop
          case c @ ('(' | '{' | '[') =>
            if (depth > 0) current.append(c)
            depth += 1; i += 1
          case c @ (')' | '}' | ']') =>
            depth -= 1; i += 1
            if (depth == 0) done = true else current.append(c)
          case ',' if depth == 1 =>
            args += current.toString; current.clear(); i += 1
          case c =>
            current.append(c); i += 1
        }
      }
      args += current.toString
      // a string, with or without `s`, is read above; what is left are the arguments that are code
      val code = args.result().map(_.replaceAll("""\b[sfr]?""""", "").replace("\"\"", "").replace("+", "").trim)
        .filter(a => a.nonEmpty && !a.contains("=>"))
      val cause = code.lastOption.exists(Throwables)
      code.dropRight(if (cause) 1 else 0).foreach(v => values += v -> false)
      Read(values.result(), literals.result(), cause)
    }

    private def string(start: Int, stop: Int, interpolated: Boolean): Unit = {
      val quote = if (text.startsWith("\"\"\"", start)) 3 else 1
      val body = text.substring(start + quote, stop - quote)
      if (!interpolated) literals += body
      else {
        val literal = new StringBuilder
        var i = 0
        while (i < body.length) {
          if (body.startsWith("$$", i)) i += 2
          else if (body.startsWith("${", i)) {
            val end = blockEnd(body, i + 1)
            values += body.substring(i + 2, end - 1).trim -> true
            literals += literal.toString; literal.clear()
            i = end
          } else if (body(i) == '$' && i + 1 < body.length && (body(i + 1).isLetter || body(i + 1) == '_')) {
            val end = (i + 1 until body.length).find(j => !body(j).isLetterOrDigit && body(j) != '_').getOrElse(body.length)
            values += body.substring(i + 1, end) -> true
            literals += literal.toString; literal.clear()
            i = end
          } else { literal.append(body(i)); i += 1 }
        }
        literals += literal.toString
      }
    }
  }
}
