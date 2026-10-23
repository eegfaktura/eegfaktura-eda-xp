package at.energydash.mqtt

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{Behavior, SupervisorStrategy}
import org.apache.pekko.stream.connectors.mqtt.scaladsl.{MqttFlow, MqttMessageWithAck}
import org.apache.pekko.stream.connectors.mqtt.{MqttConnectionSettings, MqttMessage, MqttQoS, MqttSubscriptions}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, RunnableGraph, Sink, Source}
import org.apache.pekko.stream.{ActorAttributes, BoundedSourceQueue, QueueOfferResult, Supervision}
import org.apache.pekko.util.ByteString
import at.energydash.config.Config.MqttConfig
import at.energydash.domain.EbMsMessage
import at.energydash.domain.enums.EbMsProcessType
import at.energydash.mqtt.MqttProtocol._
import at.energydash.mqtt.path.MqttPaths
import at.energydash.utils.ActorContextImplicits
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.Logger

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLContext
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.DurationInt

trait EdaEvent {
  val protocol: String
  val message: EbMsMessage
}

case class CommandMessage (
  val tenant: String,
  val command: String,
  val payload: Json,
)

case class MqttBaseTopicProvider(base: String)

object MqttProtocol {

  sealed trait MqttCmd

  sealed trait MqttMessageCmd extends MqttCmd {
    val ack: Option[() => Future[Done]]
  }

  case class EdaInboundMessage(protocol: String, message: EbMsMessage) extends EdaEvent

  /**
   * Hand-over of one inbound message to the publishing stream (261005-ca1). Completes once the
   * message is in the stream's buffer (published from there, also after a reconnect of the client),
   * fails when it is not taken: client not connected (start, restart backoff), buffer full, or
   * refused before (aggregator). Exactly one side decides: the stream claims it before offering, the
   * sender claims it when giving up, so a message the sender gave up on is never published.
   */
  final class Handover {
    private val claimed = new AtomicBoolean(false)
    private val result = Promise[Done]()
    def future: Future[Done] = result.future
    /** Taken by the stream; false when the sender already gave up (then it must not be published). */
    def claim(): Boolean = claimed.compareAndSet(false, true)
    def accepted(): Unit = result.trySuccess(Done)
    def refused(reason: Throwable): Unit = result.tryFailure(reason)
    /** Sender gives up; false when the stream already took the message (its result follows at once). */
    def giveUp(reason: Throwable): Boolean = claim() && result.tryFailure(reason)
  }

  case class EdaEventReceived(ev: EdaEvent, ack: Option[() => Future[Done]], handover: Option[Handover] = None) extends MqttMessageCmd

  case class EdaMessageCommand(command: CommandMessage, ack: Option[() => Future[Done]]) extends MqttMessageCmd

  case object MQTTConnected extends MqttCmd

  case object StreamCompleteInmediate extends MqttCmd

  case class TerminatedWithError(err: Throwable) extends MqttCmd

  case class CamFinished(id: String) extends MqttCmd

  case object TerminatedOk extends MqttCmd

  case object Terminate extends MqttCmd

}
object MqttSystem extends ActorContextImplicits with MqttPaths {
  import at.energydash.domain.JsonImplicit._

  def apply(cfg: MqttConfig): Behavior[MqttCmd] = {
    val baseTopic = cfg.base_name.getOrElse("eda2mqtt")
    Behaviors.supervise[MqttCmd] {
      Behaviors.setup { implicit context =>

        context.setLoggerName(MqttSystem.getClass)
        context.log.info(s"starting MQTT client...")

//        val act = context.messageAdapter[EdaEvent](ev => EdaEventReceived(ev, None))

        val src = mqttStreamSource(baseTopic, connectionSettings(cfg))(context.log)
        val ((mqttStream, subscribed), sinkResult) = src.run()
        // add mqtt stream event handlers
        sinkResult.recover {
          case e: Throwable =>
            context.self ! TerminatedWithError(e)
            throw e
        }
        subscribed.map { _ =>
          context.self ! MQTTConnected
        }

        Behaviors.receiveMessagePartial[MqttCmd] {
          case MQTTConnected =>
            context.log.info("mqtt client successfully connected")
            connected(mqttStream)
          case cmd: MqttMessageCmd =>
            // dropped silently here before (261005-ca1): the sender fails instead, or it is logged
            notPublished(cmd, "MQTT client not connected")
            Behaviors.same
          case TerminatedWithError(err) =>
            context.log.error("mqtt client connection error", err)
            // stream already failed. just force restart through supervisor
            throw err
          case Terminate =>
            mqttStream.complete()
            terminatingOk(context)
        }
      }
    }.onFailure(SupervisorStrategy.restartWithBackoff(minBackoff = 4.seconds, maxBackoff = 1.minute, randomFactor = 0.2))
  }

  /** A message that is not published: the sender of a hand-over fails (and logs it), anything else is logged here. */
  private def notPublished(cmd: MqttMessageCmd, reason: String)(implicit context: ActorContext[MqttCmd]): Unit = cmd match {
    case EdaEventReceived(_, _, Some(h)) => h.refused(new IllegalStateException(reason))
    case EdaEventReceived(ev, _, None) => context.log.error(s"MQTT event (conversation ${ev.message.conversationId}) not published: $reason")
    case EdaMessageCommand(command, _) => context.log.error(s"MQTT command ${command.command} not published: $reason")
  }

  /** Connected: messages go into the publishing stream (package-visible for tests). */
  private[mqtt] def connected(mqttStream: BoundedSourceQueue[MqttMessageCmd]): Behavior[MqttCmd] = {
    Behaviors.setup { implicit context =>
      def offer(cmd: MqttMessageCmd): Either[String, Unit] = mqttStream.offer(cmd) match {
        case QueueOfferResult.Enqueued => Right(())
        case QueueOfferResult.Dropped => Left("MQTT publish buffer full")
        case QueueOfferResult.Failure(e) => Left(s"MQTT publish stream failed: ${e.getMessage}")
        case QueueOfferResult.QueueClosed => Left("MQTT publish stream closed")
      }
      Behaviors.receiveMessagePartial {
        case ev: EdaEventReceived =>
          ev.handover match {
            case Some(h) =>
              if (h.claim()) offer(ev).fold(reason => h.refused(new IllegalStateException(reason)), _ => h.accepted())
            case None =>
              offer(ev).left.foreach(notPublished(ev, _))
          }
          Behaviors.same
        case cmd: EdaMessageCommand =>
          offer(cmd).left.foreach(notPublished(cmd, _))
          Behaviors.same
        case TerminatedWithError(err) =>
          context.log.error("mqtt client connection error", err)
          // stream already failed. just force restart through supervisor
          throw err
        case Terminate =>
          mqttStream.complete()
          terminatingOk(context)
        case TerminatedOk =>
          Behaviors.same
      }
    }
  }

  private def terminatingOk(context: ActorContext[MqttCmd]): Behavior[MqttCmd] =
    Behaviors.receiveMessagePartial {
      case TerminatedOk =>
        Behaviors.stopped
      case cmd: MqttMessageCmd =>
        notPublished(cmd, "MQTT client terminating")(context)
        Behaviors.same
      case TerminatedWithError(err) =>
        context.log.error("mqtt client connection error", err)
        Behaviors.stopped
      case _ =>
        Behaviors.same
    }

  /** Events waiting for the client, e.g. while it reconnects to the broker; a full buffer refuses new ones. */
  private val BufferSize = 2000

  private def mqttStreamSource(baseTopic: String, settings: MqttConnectionSettings)(implicit logger: Logger): RunnableGraph[((BoundedSourceQueue[MqttMessageCmd], Future[Done]), Future[Done])] = {
    val mqttFlow: Flow[MqttMessageWithAck, MqttMessageWithAck, Future[Done]] =
      MqttFlow.atLeastOnceWithAck(
        settings,
        MqttSubscriptions.empty,
        bufferSize = 16,
        MqttQoS.AtLeastOnce
      )
    val alwaysStop: Supervision.Decider = _ => Supervision.Stop
    val stratAlwaysStop = ActorAttributes.supervisionStrategy(alwaysStop)

    implicit val baseTopicProv: MqttBaseTopicProvider = MqttBaseTopicProvider(baseTopic)

    Source
      .queue[MqttMessageCmd](BufferSize)
      .map { edaEv =>
        event2Mqtt(edaEv) match {
          case Some(e) if edaEv.ack.isDefined =>
            Some(e.withAck(edaEv.ack.get))
          case Some(e) =>
            Some(e.withoutAck())
          case _ => None
        }
      }
      .filter(_.isDefined)
      .map(_.get)
      .log("MQTT SERVER", x => {
        s"Send MQTT Message to ${x.message.topic} [${x.message.qos.get.value}]"
      })
      .viaMat(mqttFlow)(Keep.both)
      .toMat(Sink.ignore)(Keep.both)
      .withAttributes(stratAlwaysStop)
  }

  private def connectionSettings(cfg: MqttConfig): MqttConnectionSettings = {
    Option(MqttConnectionSettings(
      s"${if (cfg.ssl) "ssl" else "tcp"}://${cfg.host}:${cfg.port}",
      s"eda2mqtt-client-messages",
      new MemoryPersistence()
    )
      .withAutomaticReconnect(true)
      .withCleanSession(false)).map { v => if (cfg.ssl) v.withSocketFactory(SSLContext.getDefault.getSocketFactory) else v}.get
  }

  private def event2Mqtt(ev: MqttMessageCmd)(implicit _btp: MqttBaseTopicProvider): Option[MqttMessage] = (ev match {
    // transform some events?
    case ev => moduleEvent2Mqtt(ev)
  }).map(ev => ev.withTopic(s"${_btp.base}/${ev.topic}").withQos(MqttQoS.AtLeastOnce)) // prepend base topic

  private def moduleEvent2Mqtt(ev: MqttMessageCmd)(implicit _btp: MqttBaseTopicProvider): Option[MqttMessage] = {
    ev match {
      case EdaEventReceived(ev, _, _) => eventToMqttMessage(ev)
      case EdaMessageCommand(cmd, _) => commandToMqttMessage(cmd)
    }
  }

  private def eventToMqttMessage(event: EdaEvent): Option[MqttMessage] = {
    val value = event.message.asJson.deepDropNullValues.noSpaces
    val msg = encodeProtocolPayload(event.protocol, value)
    Some(MqttMessage(s"${edaProtocolModulePath(event.message.receiver, event.protocol)}", ByteString(msg)).withQos(MqttQoS.atLeastOnce).withRetained(false))
  }

  /** Wire format consumed by eegfaktura-energystore. */
  private[mqtt] def encodeProtocolPayload(protocol: String, value: String): String =
    protocol match {
      case energyResponse if energyResponse == EbMsProcessType.PROCESS_ENERGY_RESPONSE.toString =>
        Base64.getEncoder.encodeToString(gzip(value.getBytes("UTF-8")))
      case _ => value
    }

  private def commandToMqttMessage(command: CommandMessage): Option[MqttMessage] = {
    val value = command.payload.deepDropNullValues.noSpaces
    Some(MqttMessage(s"${edaCommandModulePath(command.tenant, command.command)}", ByteString(value)).withQos(MqttQoS.atLeastOnce).withRetained(false))
  }

  private def gzip(data: Array[Byte]): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val gzip = new GZIPOutputStream(baos)
    gzip.write(data)
    gzip.close()
    baos.toByteArray
  }

  private implicit class MqttMessageAckExt(msg: MqttMessage) {
    def withAck(f: () => Future[Done]): MqttMessageWithAck = {
      new MqttMessageWithAck {
        override val message: MqttMessage = msg

        override def ack(): Future[Done] = f()
      }
    }

    def withoutAck(): MqttMessageWithAck = {
      new MqttMessageWithAck {
        override val message: MqttMessage = msg

        override def ack(): Future[Done] = Future.successful(Done)
      }
    }
  }
}
