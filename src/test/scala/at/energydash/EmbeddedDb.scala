package at.energydash

import com.opentable.db.postgres.embedded.EmbeddedPostgres
import com.typesafe.scalalogging.LazyLogging
import org.flywaydb.core.Flyway
import slick.basic.DatabaseConfig
import slick.jdbc.{JdbcProfile, PostgresProfile}

trait EmbeddedDb extends LazyLogging {

  val server: EmbeddedPostgres = EmbeddedPostgres
    .builder()
    .setPort(54325)
    .start()

  implicit val schema: String = "eda"
  val driver = PostgresProfile
  import driver.api.Database

  //  implicit val db: Database = Database.forURL(url = server.getJdbcUrl("postgres","testdb"), driver = "org.postgresql.Driver")
  implicit val db: DatabaseConfig[JdbcProfile] = DatabaseConfig.forConfig[JdbcProfile]("slick.pgsql.local")

  val path: String = System.getProperty("user.dir")
  private[this] val flyway = Flyway.configure
    .dataSource(server.getDatabase("postgres", "postgres"))
    .locations(s"filesystem:$path/src/test/resources/db.migration")
//  flyway.setDataSource(server.getDatabase("postgres", "postgres"))
//  val path: String = System.getProperty("user.dir")
//  //path for SQL file
//  flyway.setLocations(s"filesystem:$path/src/test/resources/db.migration")
  flyway.load().migrate()
}