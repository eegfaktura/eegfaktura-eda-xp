package at.energydash.mqtt

import org.apache.pekko.connectors.mqtt.MqttConnectionSettings

final case class MqttSourceSettings(connectionSettings: MqttConnectionSettings, topics: Map[String, Int])