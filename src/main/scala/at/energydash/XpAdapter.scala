package at.energydash

import at.energydash.actors.SupervisorActor
import at.energydash.logging.LogStartup

object XpAdapter extends LogStartup with App {

  MigrationRunner.migrate()

  SupervisorActor.start()
}
