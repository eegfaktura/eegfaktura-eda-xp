package at.energydash.logging

import io.circe.{CursorOp, DecodingFailure, ParsingFailure}

/** Error texts that are safe for the log (never-log rule: no EDA payloads). */
object LogText {

  private val ParserFailurePrefix = "Error while parsing <"
  private val ParserErrorMarker = ": parser error "

  /**
   * The message of an error without document content. scalaxb's `ParserFailure` names the whole XML element it
   * failed on (`Error while parsing <…the document…>: parser error "…" while parsing /path`); only the part after
   * the element is kept. Any other message is returned as is.
   */
  def errorText(message: String): String =
    if (message == null) ""
    else if (!message.startsWith(ParserFailurePrefix)) message
    else message.lastIndexOf(ParserErrorMarker) match {
      case -1 => "Error while parsing the document (content not logged)"
      case i => "Error while parsing the document:" + message.substring(i + 1)
    }

  /** `true` when the error or one of its causes has the document in its message (scalaxb's `ParserFailure`). */
  def carriesDocument(ex: Throwable): Boolean =
    Iterator.iterate(ex)(_.getCause).takeWhile(_ != null).take(16).exists(e => Option(e.getMessage).exists(_.startsWith(ParserFailurePrefix)))

  def errorText(ex: Throwable): String = s"${ex.getClass.getName}: ${errorText(ex.getMessage)}"

  /** A failed JSON decode without the value: circe's message quotes the offending value (a metering point, say). */
  def decodeError(error: io.circe.Error): String = error match {
    case failure: DecodingFailure => s"Request cannot be decoded at ${CursorOp.opsToPath(failure.history)}"
    case _: ParsingFailure => "Request is not valid JSON"
  }

  private val EmailAddress = """[^\s<>"'`(),;:]+@[^\s<>"'`(),;:]+""".r

  /** The text with every e-mail address replaced by `<address>` (javax.mail names the address in its errors). */
  def withoutEmailAddresses(text: String): String =
    if (text == null) "" else EmailAddress.replaceAllIn(text, "<address>")
}
