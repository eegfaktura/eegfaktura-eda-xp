package at.energydash.domain.dao

import com.github.tminglei.slickpg.json.PgJsonExtensions
import com.github.tminglei.slickpg._
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

object Db {
  /** The one connection pool of the JVM (XP-CQ-12); it opens on first use and is closed by [[close]]. */
  lazy val config: DatabaseConfig[PostgresProfile] = DatabaseConfig.forConfig[PostgresProfile]("slick.pgsql.local")

  def close(): Unit = config.db.close()
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