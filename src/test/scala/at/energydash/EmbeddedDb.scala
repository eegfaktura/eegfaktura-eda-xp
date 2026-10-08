package at.energydash

import at.energydash.testsupport.TestDb
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

/** Mix-in for specs that need the database: the shared embedded PostgreSQL of the test JVM ([[TestDb]]). */
trait EmbeddedDb {
  TestDb.start()
  implicit val schema: String = "eda"
  implicit lazy val db: DatabaseConfig[PostgresProfile] = TestDb.dbConfig
}
