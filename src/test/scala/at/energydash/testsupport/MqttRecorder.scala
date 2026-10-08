package at.energydash.testsupport

import org.eclipse.paho.client.mqttv3._
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import scala.concurrent.duration._

/** A plain MQTT client on the test broker that records what arrives on a topic filter. */
final class MqttRecorder(filter: String = "#") {
  final case class Received(topic: String, payload: String, qos: Int, retained: Boolean)

  private val queue = new LinkedBlockingQueue[Received]()
  private val client = new MqttClient(TestBroker.Url, s"recorder-${System.nanoTime()}", new MemoryPersistence)
  client.connect()
  client.subscribe(filter, 1, (topic: String, m: MqttMessage) =>
    queue.add(Received(topic, new String(m.getPayload, "UTF-8"), m.getQos, m.isRetained)))

  def next(timeout: FiniteDuration = 5.seconds): Option[Received] = Option(queue.poll(timeout.toMillis, MILLISECONDS))

  def publish(topic: String, payload: String): Unit = client.publish(topic, payload.getBytes("UTF-8"), 1, false)

  def close(): Unit = { client.disconnect(); client.close() }
}
