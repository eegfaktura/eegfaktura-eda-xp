package at.energydash

import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import at.energydash.actors.{Start, SupervisorActor}
import at.energydash.domain.dao.Db
import at.energydash.logging.LogStartup

import scala.concurrent.Future

object XpAdapter extends LogStartup with App {

  MigrationRunner.migrate()

  private val supervisor = ActorSystem(SupervisorActor(), "supervisor")
  CoordinatedShutdown(supervisor).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "close-database-pool") { () =>
    Future { Db.close(); Done }(supervisor.executionContext)
  }
  supervisor ! Start
}
