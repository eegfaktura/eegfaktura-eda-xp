package at.energydash

import com.typesafe.config.ConfigFactory
import org.flywaydb.core.Flyway

object MigrationRunner {
  def migrate(): Unit = {
    import scala.jdk.CollectionConverters._

    val config = ConfigFactory.load()

    // Read flyway config from application.conf
    val flywayConfig = config.getConfig("flyway")

    // Convert Java List -> Scala List
    val locations: List[String] =
      flywayConfig.getStringList("locations").asScala.toList

    val flyway = Flyway.configure()
      .dataSource(
        flywayConfig.getString("url"),
        flywayConfig.getString("user"),
        flywayConfig.getString("password")
      )
      .locations(locations: _*)
      .baselineOnMigrate(true)
      .defaultSchema("eda")
      .load()
    flyway.migrate()
  }
}
