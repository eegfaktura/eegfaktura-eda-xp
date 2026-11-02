package at.energydash

import org.apache.pekko.actor.typed.ActorSystem
import at.energydash.actors.{Start, SupervisorActor}
import at.energydash.logging.LogStartup

object XpAdapter extends LogStartup with App {

  MigrationRunner.migrate()

  private val supervisor = ActorSystem(SupervisorActor(), "supervisor")
  supervisor ! Start
}
