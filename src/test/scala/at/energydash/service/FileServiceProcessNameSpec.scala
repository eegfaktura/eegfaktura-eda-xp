package at.energydash.service

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.Materializer
import org.scalatest.wordspec.AnyWordSpecLike

/** The part name of an `/admin/upload` file decides the protocol (`FileService.parseProcessName`). */
class FileServiceProcessNameSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  private val service = new FileServiceImpl(system, createTestProbe[at.energydash.actors.MqttPublisher.MqttCommand]().ref)(Materializer(system))

  "parseProcessName" should {
    "split protocol and version" in {
      service.parseProcessName("CR_MSG_03.03") shouldBe Some(("CR_MSG", "03.03"))
      service.parseProcessName("ANTWORT_ECON_01.10") shouldBe Some(("ANTWORT_ECON", "01.10"))
    }
    "accept a name without version" in {
      service.parseProcessName("ANTWORT_ECON") shouldBe Some(("ANTWORT_ECON", null))
    }
    "map garbage to ERROR (pinned: the caught MatchError)" in {
      service.parseProcessName("not a process!") shouldBe Some(("ERROR", ""))
    }
  }
}
