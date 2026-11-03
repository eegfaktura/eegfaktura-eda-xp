package at.energydash.testsupport

import at.energydash.MigrationRunner
import com.opentable.db.postgres.embedded.EmbeddedPostgres
import com.typesafe.config.ConfigFactory
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import java.sql.Connection
import java.time.Duration
import scala.io.Source
import scala.util.Using

/**
 * One embedded PostgreSQL per test JVM on the port of `application-test.conf` (`eda.test.db.port`), migrated with the
 * production migrations (`MigrationRunner`, schema `eda`) plus the backend's `base.EEG` (test-only DDL,
 * outside Flyway). Specs call [[TestDb.start]] (idempotent), seed their own rows and [[TestDb.reset]].
 */
object TestDb {
  val Port: Int = ConfigFactory.load().getInt("eda.test.db.port")

  // The first start (initdb) can take longer than the library's 10 s default on a loaded host. A start that
  // fails anyway closes what it started, so the next spec does not meet an orphaned server (XP-CQ-37).
  private lazy val server: EmbeddedPostgres = {
    val pg = EmbeddedPostgres.builder().setPort(Port).setPGStartupWait(Duration.ofSeconds(60)).start()
    sys.addShutdownHook(pg.close())
    try {
      MigrationRunner.migrate()
      Using.resource(pg.getPostgresDatabase.getConnection) { c =>
        c.createStatement().execute(Using.resource(Source.fromResource("testdb/base_eeg.sql"))(_.mkString))
      }
    } catch { case e: Throwable => pg.close(); throw e }
    pg
  }

  /** Starts (once) and migrates the database. */
  def start(): Unit = server

  /** Slick configuration the production code uses (`slick.pgsql.local`), pointing at the embedded server. */
  lazy val dbConfig: DatabaseConfig[PostgresProfile] = { start(); at.energydash.domain.dao.Db.config }

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
