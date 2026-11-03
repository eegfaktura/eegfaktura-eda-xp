package at.energydash.logging

import io.circe.{CursorOp, DecodingFailure, ParsingFailure}

import scala.util.matching.Regex

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

  /**
   * The message of an exception of the class `className` for the log: without a document ([[errorText]]) and without
   * e-mail addresses. A `MatchError` has the text of the value it failed on, which can be the whole document: dropped.
   */
  def exceptionText(className: String, message: String): String =
    if (className == classOf[MatchError].getName) "(content not logged)" else withoutEmailAddresses(errorText(message))

  /** A failed JSON decode without the value: circe's message quotes the offending value (a metering point, say). */
  def decodeError(error: io.circe.Error): String = error match {
    case failure: DecodingFailure => s"Request cannot be decoded at ${CursorOp.opsToPath(failure.history)}"
    case _: ParsingFailure => "Request is not valid JSON"
  }

  /** A run of characters between separators; a single class, so the match takes time linear in the length. */
  private val Run = """[^\s<>"'`(),;:]+""".r

  /**
   * The text with every e-mail address replaced by `<address>` (javax.mail names the address in its errors): a run of
   * characters between separators that has an `@` inside it, not at either end.
   */
  def withoutEmailAddresses(text: String): String =
    if (text == null) ""
    else if (text.indexOf('@') < 0) text
    else Run.replaceAllIn(text, m => {
      val run = m.matched
      val at = run.indexOf('@', 1)
      if (at > 0 && at < run.length - 1) "<address>" else Regex.quoteReplacement(run)
    })
}
