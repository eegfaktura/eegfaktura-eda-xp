package at.energydash.testsupport

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._

/** Captures the log events of one logger while `body` runs (synchronous code; [[during]] for other threads). */
object LogCapture {
  def apply[T](loggerName: String)(body: => T): (T, List[ILoggingEvent]) =
    during(loggerName) { events =>
      val result = body
      (result, events())
    }

  /**
   * Captures the log events of one logger while `test` runs, also those logged on other threads (actors, streams):
   * `test` gets a function that returns the events so far. The appender adds under its own lock, so the copy does too.
   */
  def during[T](loggerName: String)(test: (() => List[ILoggingEvent]) => T): T = {
    val logger = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext].getLogger(loggerName)
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    try test(() => appender.synchronized(appender.list.asScala.toList))
    finally logger.detachAppender(appender)
  }

  def messages(events: List[ILoggingEvent]): List[String] = events.map(_.getFormattedMessage)
}
