package at.energydash.config

import com.typesafe.config.{ConfigFactory, Config => AkkaConfig}

import java.time.Duration

object Config {
  import scala.jdk.CollectionConverters._

  case class MqttConfig(host: String, port: Int, username: String, password: String, ssl: Boolean, required: Option[Boolean], base_name: Option[String])

  case class MqttMailConfig(url: String, topic: String, qos: Int, consumerId: String)

//  lazy val config = ConfigFactory.load("application-test.conf")
  lazy val config = ConfigFactory.load()
  config.checkValid(ConfigFactory.defaultReference)

  lazy val emailPersistInbox = config.getString("epmsmail.mail.inbox")
  lazy val adminSrvConfig: AkkaConfig = config.getConfig(s"epmsmail.admin")
  lazy val emailDomain = (tenant: String) => config.getString(s"epmsmail.mail.${tenant}.domain")
  lazy val interval: String => Duration = (domain: String) => config.getDuration(s"epmsmail.mail.${domain}.interval")
  def getMqttMailConfig: MqttMailConfig = MqttMailConfig(
    s"tcp://${config.getString("epmsmail.mqtt.host")}:${config.getInt("epmsmail.mqtt.port")}",
    config.getString("epmsmail.mqtt.sub-topic"),
    config.getInt("epmsmail.mqtt.qos"),
    config.getString("epmsmail.mqtt.consumer-id")
  )

  def getMqttConfig(): MqttConfig = MqttConfig(
    config.getString("epmsmail.mqtt.host"), config.getInt("epmsmail.mqtt.port"), "", "", false,
    None, Some("eda/response"))

  lazy val energyTopic = config.getString("epmsmail.mqtt.topics.energyTopic")
  lazy val cmTopic = config.getString("epmsmail.mqtt.topics.cmTopic")
  lazy val cpTopic = config.getString("epmsmail.mqtt.topics.cpTopic")
  lazy val errorTopic = config.getString("epmsmail.mqtt.topics.errorTopic")

  def getDomain(domain: String):Map[String, Object] = config.getConfig(s"epmsmail.mail.${domain}.javaxmail").entrySet().asScala.map(e => e.getKey -> e.getValue.unwrapped()).toMap

  def interfaceMode = config.getString("app.interface.mode")

  def edaKepServer: AkkaConfig = config.getConfig("app.kepserver")
}