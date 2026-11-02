package at.energydash

import org.apache.pekko.actor.typed.ActorSystem
import at.energydash.actors.{Start, SupervisorActor}
import at.energydash.logging.{LogSettings, LogStartup}

object XpAdapter extends LogStartup with App {

  LogSettings.logStart()
  MigrationRunner.migrate()

  private val supervisor = ActorSystem(SupervisorActor(), "supervisor")
  supervisor ! Start
}
