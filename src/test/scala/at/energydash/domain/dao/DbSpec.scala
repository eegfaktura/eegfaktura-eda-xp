package at.energydash.domain.dao

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Await
import scala.concurrent.duration._

/** XP-CQ-12: the one pool of the JVM, closed when the application's actor system shuts down. */
class DbSpec extends AnyWordSpec with Matchers {
  "Db" should {
    "hand out the same pool on every use" in {
      Db.config should be theSameInstanceAs Db.config
    }
    "close the pool when the actor system shuts down" in {
      val closed = new AtomicBoolean(false)
      // a stand-in for the close: the real pool is shared by every spec of the test JVM
      val system = ActorSystem(Behaviors.empty[Unit], "db-close-on-shutdown")
      Db.closeOnShutdown(system, () => closed.set(true))
      closed.get shouldBe false
      system.terminate()
      Await.ready(system.whenTerminated, 10.seconds)
      closed.get shouldBe true
    }
  }
}
