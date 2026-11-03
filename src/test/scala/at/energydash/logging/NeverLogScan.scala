package at.energydash.logging

import java.util.Locale

/**
 * The scan of [[NeverLogSpec]]: reads the log calls of Scala source, without a compiler. Every value of a call is a
 * finding (default-deny) unless the spec's allowlist names it for its file; a value with a word of [[Words]] in one
 * of its identifiers, `toString`, the text of an exception or an exception in the text is one by its rule. The text
 * of string literals is not scanned.
 */
object NeverLogScan {
  /** One log call: its interpolated and argument values (an exception as the last argument is not one). */
  final case class Call(file: String, line: Int, values: Seq[(String, Boolean)])

  /** A value of a call at `file:line` and the rule it breaks. */
  final case class Hit(file: String, line: Int, term: String, rule: String) {
    override def toString: String = s"$file:$line `$term`: $rule"
  }

  val NotKnownSafe = "value not known to be safe"
  val PersonalWord = "personal data or secret"
  val ToStringCall = "toString"
  val ExceptionInMessage = "text of an exception in the message, attach the exception instead"
  val ExceptionInText = "exception in the text instead of as the last argument"

  /**
   * The never-log words of all services, compared with the words of an identifier (camelCase, acronyms and snake_case
   * split as in admin-backend, lower case), alone or as two adjacent words: `lastName`, `api_key`, `SMTPPassword`.
   */
  val Words: Set[String] = Set("password", "passwd", "pwd", "secret", "token", "jwt", "bearer", "authorization",
    "credential", "creds", "cookie", "apikey", "iban", "bic", "firstname", "lastname", "vorname", "nachname", "name1",
    "name2", "street", "phone", "email", "mailaddress", "recipient", "sentto", "owner", "accountholder", "username",
    "userid", "sub", "subject", "payload", "body")

  private val Throwables = Set("e", "ex", "err", "exception", "f", "me")
  /** The text of an exception: the encoder masks it only on the exception, which the call attaches instead. */
  private val ExceptionText = ("""\b(?:getMessage|getLocalizedMessage)\b|errorText\(\s*(?:""" + Throwables.mkString("|") + """)\s*\)""").r
  private val Identifier = """[A-Za-z_][A-Za-z0-9_]*""".r
  private val WordBoundary = """(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_"""
  private val Start = """(?:\b(?:logger|log)\.(?:trace|debug|info|warn|error)|\.setMessage|\.log)\(""".r

  /** The word of [[Words]] in `identifier`, if any. */
  def personalWord(identifier: String): Option[String] = {
    val words = identifier.split(WordBoundary).filter(_.nonEmpty).map(_.toLowerCase(Locale.ROOT)).toSeq
    (1 to 2).iterator.flatMap(n => words.sliding(n).map(_.mkString)).find(Words)
  }

  def findings(call: Call): Seq[Hit] = call.values.map { case (value, interpolated) =>
    // the text of a string literal inside the value is not scanned
    val code = value.replaceAll("\"[^\"]*\"", "\"\"")
    // every identifier, so every segment of a dotted value: `email.data.conversationId` names the mail
    val word = Identifier.findAllIn(code).flatMap(personalWord).nextOption()
    val rule =
      if (ExceptionText.findFirstIn(code).isDefined) ExceptionInMessage
      else if (word.isDefined) s"$PersonalWord: ${word.get}"
      else if (Identifier.findAllIn(code).contains("toString")) ToStringCall
      else if (interpolated && Throwables(value)) ExceptionInText
      else NotKnownSafe
    Hit(call.file, call.line, value, rule)
  }

  /** The log calls in `text`, comments and strings outside of a call are skipped. */
  def logCalls(file: String, text: String): Seq[Call] = {
    val code = withoutCommentsAndStrings(text)
    Start.findAllMatchIn(code).toList.flatMap { m =>
      List(Call(file, text.take(m.start).count(_ == '\n') + 1, new CallReader(text, m.end - 1).read()))
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

    def read(): Seq[(String, Boolean)] = {
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
        // a function that only builds a string (`x => s"…"`, pekko's `.log(name, f)`) is read through that string
        .filter(a => a.replaceAll("""\b\w+\s*=>""", "").replaceAll("""[{}()\s]""", "").nonEmpty)
      val cause = code.lastOption.exists(Throwables)
      code.dropRight(if (cause) 1 else 0).foreach(v => values += v -> false)
      values.result()
    }

    private def string(start: Int, stop: Int, interpolated: Boolean): Unit = {
      val quote = if (text.startsWith("\"\"\"", start)) 3 else 1
      val body = text.substring(start + quote, stop - quote)
      if (interpolated) {
        var i = 0
        while (i < body.length) {
          if (body.startsWith("$$", i)) i += 2
          else if (body.startsWith("${", i)) {
            val end = blockEnd(body, i + 1)
            values += body.substring(i + 2, end - 1).trim -> true
            i = end
          } else if (body(i) == '$' && i + 1 < body.length && (body(i + 1).isLetter || body(i + 1) == '_')) {
            val end = (i + 1 until body.length).find(j => !body(j).isLetterOrDigit && body(j) != '_').getOrElse(body.length)
            values += body.substring(i + 1, end) -> true
            i = end
          } else i += 1
        }
      }
    }
  }
}
