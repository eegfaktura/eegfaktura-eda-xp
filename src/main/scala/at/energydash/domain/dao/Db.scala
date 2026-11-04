package at.energydash.domain.dao

import com.github.tminglei.slickpg.json.PgJsonExtensions
import com.github.tminglei.slickpg._
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import scala.concurrent.Future

object Db {
  /** The one connection pool of the JVM (XP-CQ-12); it opens on first use and is closed by [[close]]. */
  lazy val config: DatabaseConfig[PostgresProfile] = DatabaseConfig.forConfig[PostgresProfile]("slick.pgsql.local")

  def close(): Unit = config.db.close()

  /** Closes the pool (or runs `close`) when `system` shuts down, after the services have stopped. */
  def closeOnShutdown(system: ActorSystem[_], close: () => Unit = () => Db.close()): Unit =
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "close-database-pool") { () =>
      Future { close(); Done }(system.executionContext)
    }
}

trait PostgresProfiler
  extends ExPostgresProfile
    with PgArraySupport
    with PgRangeSupport
    with PgDateSupport
    with PgDate2Support
    with PgSearchSupport
    with PgCirceJsonSupport
    with PgEnumSupport {

  override val pgjson = "jsonb"

  override val api: MyAPI.type = MyAPI

  object MyAPI
    extends ExtPostgresAPI
      with ArrayImplicits
      with SimpleDateTimeImplicits
      with SearchImplicits
      with SearchAssistants
      with JsonImplicits

}

object PostgresProfiler extends PostgresProfiler with PgJsonExtensions