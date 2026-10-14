package at.energydash.testsupport

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._

/** Captures the log events of one logger while `body` runs (synchronous code only). */
object LogCapture {
  def apply[T](loggerName: String)(body: => T): (T, List[ILoggingEvent]) = {
    val logger = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext].getLogger(loggerName)
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    try {
      val result = body
      (result, appender.list.asScala.toList)
    } finally logger.detachAppender(appender)
  }

  def messages(events: List[ILoggingEvent]): List[String] = events.map(_.getFormattedMessage)
}
