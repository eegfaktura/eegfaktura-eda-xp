package at.energydash

import org.apache.pekko.actor.typed.ActorSystem
import at.energydash.actors.{Start, SupervisorActor}

object XpAdapter extends App {

  MigrationRunner.migrate()

  private val supervisor = ActorSystem(SupervisorActor(), "supervisor")
  supervisor ! Start
}
