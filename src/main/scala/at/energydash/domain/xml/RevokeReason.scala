package at.energydash.domain.xml

import at.energydash.domain.EbMsMessage
import org.slf4j.{Logger, LoggerFactory}

/**
 * The free-text reason of a revoke as it goes into CMRevoke `Reason` (01p00 and 01p10: string, at most 50 characters).
 * Control characters and every kind of white space (also U+00A0) become one space, the ends are trimmed; blank means
 * no reason. A reason still longer than 50 characters is dropped with a warning (message id only) — a document
 * outside the schema is never sent. The backend refuses such a reason already (400).
 */
object RevokeReason {
  val MaxLength = 50
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private def isSpace(c: Char): Boolean = Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)

  def apply(message: EbMsMessage): Option[String] = message.reason.flatMap { raw =>
    val cleaned = raw.map(c => if (isSpace(c)) ' ' else c).split(' ').filter(_.nonEmpty).mkString(" ")
    if (cleaned.isEmpty) None
    else if (cleaned.codePointCount(0, cleaned.length) > MaxLength) {
      logger.warn(s"revoke ${message.messageId.getOrElse("<no message id>")}: reason longer than $MaxLength characters, sent without reason")
      None
    } else Some(cleaned)
  }
}
