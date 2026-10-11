package at.energydash.actors.http

import com.typesafe.config.{Config => TsConfig}
import org.apache.pekko.http.scaladsl.model.Uri

import scala.jdk.CollectionConverters._
import scala.xml.NodeSeq

/**
 * Inbound messages for communities that moved to another zone (e.g. the pilot) are handed on
 * unchanged to that zone's adapter instead of being processed here. Ponton's EDA add-on delivers
 * every inbound message to one backend URL, so the zone split happens in this adapter, by the
 * ReceiverId of the Ponton header (konzept-eeg-umzug-pilot.md, "Weg B").
 */
object InboundForwarding {

  /** Set on forwarded requests; an adapter that would forward such a request again refuses it. */
  val ForwardedHeader = "X-Eegfaktura-Forwarded"

  sealed trait Decision
  case object Local extends Decision
  final case class Forward(receiver: String, target: Uri) extends Decision
  final case class Loop(receiver: String, target: Uri) extends Decision

  /** ReceiverId of the Ponton InboundDocument header, without parsing the message body. */
  def receiverOf(envelope: NodeSeq): Option[String] =
    (envelope \\ "InboundDocument" \ "Header" \ "ReceiverId").headOption.map(_.text.trim).filter(_.nonEmpty)

  /** `app.forward`: RC number -> URL of the target adapter's /pontonxp/message; keys compared upper case. */
  def targets(config: TsConfig): Map[String, Uri] =
    if (!config.hasPath("app.forward")) Map.empty
    else {
      val forward = config.getConfig("app.forward")
      forward.root().keySet().asScala.map(rc => rc.trim.toUpperCase -> Uri(forward.getString(s"\"$rc\""))).toMap
    }

  def decide(targets: Map[String, Uri], receiver: Option[String], alreadyForwarded: Boolean): Decision =
    receiver.map(_.trim.toUpperCase).flatMap(rc => targets.get(rc).map(rc -> _)) match {
      case Some((rc, target)) if alreadyForwarded => Loop(rc, target)
      case Some((rc, target)) => Forward(rc, target)
      case None => Local
    }
}
