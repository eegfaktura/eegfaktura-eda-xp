package at.energydash

import com.typesafe.slick.testkit.util.ProfileTest
import com.typesafe.slick.testkit.util.StandardTestDBs.Postgres

class MyPostgresTest extends ProfileTest(Postgres)

//class MyPostgresTest extends ProfileTest(MyPostgresTest.tdb)
//
//object MyPostgresTest {
//  def tdb = new ExternalJdbcTestDB("mypostgres") {
//    val profile = Db.getConfig.profile
//    override def localTables(implicit ec: ExecutionContext): DBIO[Vector[String]] =
//      ResultSetAction[(String,String,String, String)](_.conn.getMetaData().getTables("", "public", null, null)).map { ts =>
//        ts.filter(_._4.toUpperCase == "TABLE").map(_._3).sorted
//      }
//    override def localSequences(implicit ec: ExecutionContext): DBIO[Vector[String]] =
//      ResultSetAction[(String,String,String, String)](_.conn.getMetaData().getTables("", "public", null, null)).map { ts =>
//        ts.filter(_._4.toUpperCase == "SEQUENCE").map(_._3).sorted
//      }
//    override def capabilities = super.capabilities - TestDB.capabilities.jdbcMetaGetFunctions
//  }
//}
