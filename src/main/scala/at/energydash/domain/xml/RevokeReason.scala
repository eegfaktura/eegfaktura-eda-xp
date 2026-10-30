package at.energydash.domain.xml

import at.energydash.domain.EbMsMessage
import org.slf4j.{Logger, LoggerFactory}

/**
 * The free-text reason of a revoke as it goes into CMRevoke `Reason` (01p00 and 01p10: string, at most 50 characters).
 * Control characters and every kind of white space (also U+00A0) become one space; format characters (U+200B, U+FEFF,
 * U+202E …) and characters XML 1.0 does not allow (U+FFFE, U+FFFF, lone surrogates) are dropped; the ends are
 * trimmed; blank means no reason. The backend cleans the same way. A reason still longer than 50 characters is dropped
 * with a warning (message id only) — a document outside the schema is never sent. The backend refuses such a reason
 * already (400).
 */
object RevokeReason {
  val MaxLength = 50
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private def isSpace(cp: Int): Boolean = Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)

  // XML 1.0 Char production
  private def isXmlChar(cp: Int): Boolean = cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF) ||
    (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF)

  def clean(raw: String): String = {
    val sb = new java.lang.StringBuilder
    raw.codePoints().forEach { cp =>
      if (isSpace(cp)) sb.append(' ')
      else if (Character.getType(cp) == Character.FORMAT || !isXmlChar(cp)) () // dropped
      else sb.appendCodePoint(cp)
    }
    sb.toString.split(' ').filter(_.nonEmpty).mkString(" ")
  }

  def apply(message: EbMsMessage): Option[String] = message.reason.flatMap { raw =>
    val cleaned = clean(raw)
    if (cleaned.isEmpty) None
    else if (cleaned.codePointCount(0, cleaned.length) > MaxLength) {
      logger.warn(s"revoke ${message.messageId.getOrElse("<no message id>")}: reason longer than $MaxLength characters, sent without reason")
      None
    } else Some(cleaned)
  }
}
