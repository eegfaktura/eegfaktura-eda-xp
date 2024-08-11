package at.energydash.domain.dao

import com.github.tminglei.slickpg.json.PgJsonExtensions
import com.github.tminglei.slickpg._
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

trait Db {

  val db: PostgresProfile#Backend#Database
  val config: DatabaseConfig[PostgresProfile]
//  val pgDb: ExPostgresProfile#Backend#Database
}

object Db {
  def getConfig: DatabaseConfig[PostgresProfile] = {
    DatabaseConfig.forConfig[PostgresProfile]("slick.pgsql.local")
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