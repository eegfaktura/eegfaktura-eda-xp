package at.energydash.actors.http

import com.typesafe.config.{Config => TsConfig}
import org.apache.pekko.http.scaladsl.model.Uri

import java.util.Locale
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.xml.NodeSeq

/**
 * Inbound messages for communities that moved to another zone (e.g. the pilot) are handed on
 * unchanged to that zone's adapter instead of being processed here. Ponton's EDA add-on delivers
 * every inbound message to one backend URL, so the zone split happens in this adapter, by the
 * ReceiverId of the Ponton header: the moved community's Ponton agreements keep pointing at the
 * adapter of its old zone, which hands its messages on unchanged.
 */
object InboundForwarding {

  /** Set on forwarded requests; an adapter that would forward such a request again refuses it. */
  val ForwardedHeader = "X-Eegfaktura-Forwarded"

  sealed trait Decision
  case object Local extends Decision
  final case class Forward(receiver: String, target: Uri) extends Decision
  final case class Loop(receiver: String, target: Uri) extends Decision

  /** Deadlines of the forwarding route: body read (every message) and target answer. */
  final case class Timeouts(read: FiniteDuration, forward: FiniteDuration)

  /**
   * `app.forward-read-timeout` / `app.forward-timeout` (env FORWARD_READ_TIMEOUT / FORWARD_TIMEOUT);
   * the defaults when unset. Zero or negative stops the start.
   */
  def timeouts(config: TsConfig): Timeouts = {
    def duration(path: String, default: FiniteDuration): FiniteDuration =
      if (config.hasPath(path)) config.getDuration(path).toMillis.millis else default
    val t = Timeouts(duration("app.forward-read-timeout", DefaultReadTimeout),
                     duration("app.forward-timeout", DefaultForwardTimeout))
    if (t.read <= Duration.Zero || t.forward <= Duration.Zero)
      throw new IllegalArgumentException(s"app.forward-read-timeout and app.forward-timeout must be positive: $t")
    t
  }

  val DefaultReadTimeout: FiniteDuration = 5.seconds
  val DefaultForwardTimeout: FiniteDuration = 10.seconds

  /** ReceiverId of the Ponton InboundDocument header, without parsing the message body. */
  def receiverOf(envelope: NodeSeq): Option[String] =
    (envelope \\ "InboundDocument" \ "Header" \ "ReceiverId").headOption.map(_.text.trim).filter(_.nonEmpty)

  /**
   * `app.forward`: RC number (the Ponton ReceiverId, not the tenant — all GEA tenants of one RC number
   * move together) -> absolute http(s) URL of the target adapter's /pontonxp/message. Keys compared
   * upper case. A broken entry stops the start: running without it would process the community in
   * the wrong zone.
   */
  def targets(config: TsConfig): Map[String, Uri] =
    if (!config.hasPath("app.forward")) Map.empty
    else {
      val forward = config.getConfig("app.forward")
      val entries = forward.root().keySet().asScala.toSeq.map { key =>
        val raw = forward.getString(s"\"$key\"")
        val uri = Try(Uri(raw)).filter(u => u.isAbsolute && Set("http", "https").contains(u.scheme) && u.authority.host.address.nonEmpty)
          .getOrElse(throw new IllegalArgumentException(s"app.forward.$key must be an absolute http(s) URL, got '$raw'"))
        key.trim.toUpperCase(Locale.ROOT) -> uri
      }
      entries.groupBy(_._1).collectFirst { case (rc, dup) if dup.size > 1 => rc }.foreach { rc =>
        throw new IllegalArgumentException(s"app.forward lists $rc more than once (keys differ only in case)")
      }
      entries.toMap
    }

  def decide(targets: Map[String, Uri], receiver: Option[String], alreadyForwarded: Boolean): Decision =
    receiver.map(_.trim.toUpperCase(Locale.ROOT)).flatMap(rc => targets.get(rc).map(rc -> _)) match {
      case Some((rc, target)) if alreadyForwarded => Loop(rc, target)
      case Some((rc, target)) => Forward(rc, target)
      case None => Local
    }
}
