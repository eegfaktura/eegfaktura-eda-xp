package at.energydash.logging

import io.circe.{CursorOp, DecodingFailure, ParsingFailure}

/** Error texts that are safe for the log (never-log rule: no EDA payloads). */
object LogText {

  private val ParserFailurePrefix = "Error while parsing <"
  private val ParserErrorMarker = ": parser error "

  /**
   * The message of an error without document content. scalaxb's `ParserFailure` names the whole XML element it
   * failed on (`Error while parsing <…the document…>: parser error "…" while parsing /path`); only the part after
   * the element is kept, also when other text comes first. Any other message is returned as is.
   */
  def errorText(message: String): String =
    if (message == null) ""
    else message.indexOf(ParserFailurePrefix) match {
      case -1 => message
      case start => message.substring(0, start) + (message.lastIndexOf(ParserErrorMarker) match {
        case i if i > start => "Error while parsing the document:" + message.substring(i + 1)
        case _ => "Error while parsing the document (content not logged)"
      })
    }

  /** `true` when the error or one of its causes has the document in its message (scalaxb's `ParserFailure`). */
  def carriesDocument(ex: Throwable): Boolean =
    Iterator.iterate(ex)(_.getCause).takeWhile(_ != null).take(16).exists(e => Option(e.getMessage).exists(_.contains(ParserFailurePrefix)))

  /**
   * The message of an exception of the class `className` for the log: without a document ([[errorText]]) and without
   * e-mail addresses. A `MatchError` has the text of the value it failed on, which can be the whole document: dropped.
   */
  def exceptionText(className: String, message: String): String =
    if (className == classOf[MatchError].getName) "(content not logged)" else withoutEmailAddresses(errorText(message))

  def errorText(ex: Throwable): String = s"${ex.getClass.getName}: ${exceptionText(ex.getClass.getName, ex.getMessage)}"

  /** A failed JSON decode without the value: circe's message quotes the offending value (a metering point, say). */
  def decodeError(error: io.circe.Error): String = error match {
    case failure: DecodingFailure => s"Request cannot be decoded at ${CursorOp.opsToPath(failure.history)}"
    case _: ParsingFailure => "Request is not valid JSON"
  }

  private val Separators = " \t\n\u000B\f\r<>\"'`(),;:"

  /**
   * The text with every e-mail address replaced by `<address>` (javax.mail names the address in its errors): a run of
   * characters between separators that has an `@` inside it, not at either end. One pass, no regex: every exception
   * text comes through here on a dispatcher thread.
   */
  def withoutEmailAddresses(text: String): String =
    if (text == null) ""
    else if (text.indexOf('@') < 0) text
    else {
      def separator(c: Char) = Separators.indexOf(c.toInt) >= 0
      val out = new java.lang.StringBuilder(text.length)
      var start = 0
      while (start < text.length) {
        var end = start + 1
        if (separator(text(start))) out.append(text(start))
        else {
          var address = false
          while (end < text.length && !separator(text(end))) {
            if (text(end) == '@' && end + 1 < text.length && !separator(text(end + 1))) address = true
            end += 1
          }
          if (address) out.append("<address>") else out.append(text, start, end)
        }
        start = end
      }
      out.toString
    }
}
