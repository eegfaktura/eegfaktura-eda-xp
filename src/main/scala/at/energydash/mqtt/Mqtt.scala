package at.energydash.mqtt

import akka.stream.alpakka.mqtt.MqttConnectionSettings

final case class MqttSourceSettings(connectionSettings: MqttConnectionSettings, topics: Map[String, Int])