package at.energydash.testsupport

import at.energydash.MigrationRunner
import com.opentable.db.postgres.embedded.EmbeddedPostgres
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import java.sql.Connection
import scala.io.Source
import scala.util.Using

/**
 * One embedded PostgreSQL per test JVM on the port of `application-test.conf` (54325), migrated with the
 * production migrations (`MigrationRunner`, schema `eda`) plus the backend's `base.EEG` (test-only DDL,
 * outside Flyway). Specs call [[TestDb.start]] (idempotent), seed their own rows and [[TestDb.reset]].
 */
object TestDb {
  val Port = 54325

  private lazy val server: EmbeddedPostgres = {
    val pg = EmbeddedPostgres.builder().setPort(Port).start()
    sys.addShutdownHook(pg.close())
    MigrationRunner.migrate()
    Using.resource(pg.getPostgresDatabase.getConnection) { c =>
      c.createStatement().execute(Using.resource(Source.fromResource("testdb/base_eeg.sql"))(_.mkString))
    }
    pg
  }

  /** Starts (once) and migrates the database. */
  def start(): Unit = server

  /** Slick configuration the production code uses (`slick.pgsql.local`), pointing at the embedded server. */
  lazy val dbConfig: DatabaseConfig[PostgresProfile] = { start(); at.energydash.domain.dao.Db.getConfig }

  def withConnection[T](f: Connection => T): T = {
    start()
    Using.resource(server.getPostgresDatabase.getConnection)(f)
  }

  def exec(sql: String*): Unit = withConnection { c =>
    val st = c.createStatement()
    sql.foreach(st.execute)
  }

  def count(sql: String): Int = withConnection { c =>
    val rs = c.createStatement().executeQuery(sql)
    rs.next(); rs.getInt(1)
  }

  /** Empties every table the service writes and `base.eeg`. */
  def reset(): Unit = exec("TRUNCATE eda.tenantconfig, eda.inbox, eda.outbox, eda.conversation, base.eeg")

  def seedTenant(tenant: String, cType: String = "KEP", domain: String = "email.com"): Unit =
    exec(s"""INSERT INTO eda.tenantconfig (tenant, type, domain, host, imapport, smtpport, smtphost, username, pass,
            |  imap_security, smtp_security, active)
            |VALUES ('$tenant', '$cType', '$domain', '$domain', 143, 25, '$domain', 'test', 'test', 'STARTTLS', 'STARTTLS', true)""".stripMargin)

  def seedConversation(id: String, json: String): Unit =
    exec(s"INSERT INTO eda.conversation (id, conversation) VALUES ('$id', '${json.replace("'", "''")}'::json)")

  def seedEeg(tenant: String, communityId: String): Unit =
    exec(s"""INSERT INTO base.eeg (tenant, name, "rcNumber", area, gridoperator_code, gridoperator_name, "communityId",
            |  street, "streetNumber", city, zip, email)
            |VALUES ('$tenant', 'EEG $tenant', '$tenant', 'LOCAL', 'AT003000', 'Netz', '$communityId', 'Weg', '1', 'Ort', '1000', 'eeg@email.com')""".stripMargin)
}
