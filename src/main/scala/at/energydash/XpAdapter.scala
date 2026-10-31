package at.energydash

import org.apache.pekko.actor.typed.ActorSystem
import at.energydash.actors.{Start, SupervisorActor}
import at.energydash.logging.LogSettings

object XpAdapter extends App {

  LogSettings.stopOnInvalid()
  LogSettings.fillEmpty()
  MigrationRunner.migrate()

  private val supervisor = ActorSystem(SupervisorActor(), "supervisor")
  supervisor ! Start
}
