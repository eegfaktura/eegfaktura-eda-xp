package at.energydash.logging

/** The scan of [[NeverLogSpec]]: reads the log calls of Scala source, without a compiler. */
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
    "loggableId(messageId)" -> "Fetcher: a value of the shape of an id, or a fixed text",
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
    // texts that are built from the above or masked
    "details" -> "logSize: message code and conversation id, or a fixed text, at both callers",
    "reason" -> "fixed texts with ids; MqttPublisher's and MqttSystem's reasons name no document",
    "getMessageNumber" -> "number of a mail in the mailbox",
    "msg" -> "EdaMessage.fallbackVersion: version label, message code and conversation id",
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
  /** The text of an exception: the encoder masks it only on the exception, which the call attaches instead. */
  private val ExceptionText = ("""\b(?:getMessage|getLocalizedMessage)\b|errorText\(\s*(?:""" + Throwables.mkString("|") + """)\s*\)""").r
  private val SimplePath = """[A-Za-z_][\w.]*(\(\))?""".r
  private val Start = """(?:\b(?:logger|log)\.(?:trace|debug|info|warn|error)|\.setMessage|\.log)\(""".r

  /** The key of [[Allowed]] that covers `value`, if any. */
  def allowedEntry(value: String): Option[String] = {
    val path = value.stripSuffix("()")
    Allowed.keys.find(k => k == value || (SimplePath.matches(value) && (path == k || path.endsWith("." + k))))
  }

  def findings(call: Call): Seq[String] = {
    val where = s"${call.file}:${call.line}"
    val rules = Seq(
      call.values.find(v => ExceptionText.findFirstIn(v._1).isDefined).map(v => s"exception text in the message, attach the exception: ${v._1}"),
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
    else if (ExceptionText.findFirstIn(code).isDefined) None // reported for the call (above)
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
