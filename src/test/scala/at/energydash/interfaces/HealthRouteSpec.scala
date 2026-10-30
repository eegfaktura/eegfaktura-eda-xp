package at.energydash.interfaces

import at.energydash.actors.MqttPublisher.MqttCommand
import at.energydash.actors.http.{BodySizeLimit, HealthRoute, PontonRoute}
import at.energydash.testsupport.TestDb
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

/** `GET /healthz` and `GET /readyz` next to the Ponton routes, the way the supervisor binds them. */
class HealthRouteSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  private implicit val ec: ExecutionContext = system.executionContext
  private val publisher = createTestProbe[MqttCommand]()
  private val ponton = new PontonRoute(publisher.ref, maxBodyBytes = BodySizeLimit.maxBytes(system.settings.config))(system).pontonRoutes

  private lazy val workingDb = { TestDb.start(); HealthRoute.databaseCheck(system.settings.config) }
  // nothing listens on port 1: the connection is refused
  private lazy val brokenDb = HealthRoute.databaseCheck(ConfigFactory.parseString(
    """slick.pgsql.local.db.url = "jdbc:postgresql://127.0.0.1:1/postgres"""").withFallback(system.settings.config))

  /** Binds the Ponton routes and the health routes with `ready`, runs `f` on the base URL, unbinds. */
  private def withServer[T](ready: () => Future[Unit], timeout: FiniteDuration = HealthRoute.ReadyTimeout)(f: String => T): T = {
    val routes = ponton ~ new HealthRoute(ready, timeout)(system).healthRoutes
    val binding = Await.result(Http()(system).newServerAt("127.0.0.1", 0).bind(routes), 10.seconds)
    try f(s"http://127.0.0.1:${binding.localAddress.getPort}") finally Await.ready(binding.unbind(), 10.seconds)
  }

  private def send(req: HttpRequest): Future[(StatusCode, String)] =
    Http()(system).singleRequest(req).flatMap(res => Unmarshal(res.entity).to[String].map(res.status -> _))

  private def call(method: HttpMethod, url: String, entity: RequestEntity = HttpEntity.Empty): (StatusCode, String) =
    Await.result(send(HttpRequest(method, url, entity = entity)), 15.seconds)

  private val failing: () => Future[Unit] = () => Future.failed(new RuntimeException("database down"))

  "GET /healthz" should {
    "answer 200 ok without a token, even when the database check fails" in withServer(failing) { base =>
      call(HttpMethods.GET, s"$base/healthz") shouldBe (StatusCodes.OK, "ok")
    }
  }

  "GET /readyz" should {
    "answer 200 ok when the database answers SELECT 1" in withServer(workingDb) { base =>
      call(HttpMethods.GET, s"$base/readyz") shouldBe (StatusCodes.OK, "ok")
    }
    "answer 503 unavailable, without the reason, when the database is unreachable" in withServer(brokenDb) { base =>
      call(HttpMethods.GET, s"$base/readyz") shouldBe (StatusCodes.ServiceUnavailable, "unavailable")
    }
    "answer 200 when Postgres refuses the ping as one connection too many (SQLSTATE 53300)" in {
      // a role allowed no connection: Postgres refuses it with too_many_connections, like a saturated server
      TestDb.exec("DROP ROLE IF EXISTS readyz_full", "CREATE ROLE readyz_full LOGIN PASSWORD 'x' CONNECTION LIMIT 0")
      val saturated = HealthRoute.databaseCheck(ConfigFactory.parseString(
        """slick.pgsql.local.db { user = "readyz_full", password = "x" }""").withFallback(system.settings.config))
      val refused = intercept[java.sql.SQLException](java.sql.DriverManager.getConnection(
        s"jdbc:postgresql://127.0.0.1:${TestDb.Port}/postgres", "readyz_full", "x").close())
      refused.getSQLState shouldBe HealthRoute.TooManyConnections
      try withServer(saturated) { base =>
        call(HttpMethods.GET, s"$base/readyz") shouldBe (StatusCodes.OK, "ok")
      } finally TestDb.exec("DROP ROLE readyz_full")
    }
    "answer 503 when the check does not finish within the timeout" in
      withServer(() => Promise[Unit]().future, timeout = 300.millis) { base =>
        call(HttpMethods.GET, s"$base/readyz") shouldBe (StatusCodes.ServiceUnavailable, "unavailable")
      }
    "answer 503 when the check throws instead of failing its future" in
      withServer(() => throw new IllegalStateException("driver missing")) { base =>
        call(HttpMethods.GET, s"$base/readyz") shouldBe (StatusCodes.ServiceUnavailable, "unavailable")
      }
  }

  "The shared readiness check" should {
    /** A HealthRoute on a clock the test moves (nanoseconds); `ready` counts its starts. */
    class Fixture(result: () => Future[Unit]) {
      val clock = new AtomicLong(0L)
      val started = new AtomicInteger()
      val route = new HealthRoute(() => { started.incrementAndGet(); result() }, now = () => clock.get())(system)
      def advance(d: FiniteDuration): Unit = clock.addAndGet(d.toNanos)
    }

    "run one check for concurrent calls and give them its result" in {
      val gate = Promise[Unit]()
      val f = new Fixture(() => gate.future)
      val first = f.route.shared()
      f.advance(HealthRoute.ReuseFor * 2) // past reuseFor, but still running
      val calls = first +: (1 to 4).map(_ => f.route.shared())
      f.started.get() shouldBe 1
      gate.success(())
      Await.result(Future.sequence(calls), 5.seconds)
      f.started.get() shouldBe 1
    }
    "reuse a result younger than reuseFor and check again after it" in {
      val f = new Fixture(() => Future.unit)
      Await.result(f.route.shared(), 5.seconds)
      f.advance(HealthRoute.ReuseFor - 1.milli)
      Await.result(f.route.shared(), 5.seconds)
      f.started.get() shouldBe 1
      f.advance(1.milli)
      Await.result(f.route.shared(), 5.seconds)
      f.started.get() shouldBe 2
    }
    "start a new check when the running one hangs longer than staleAfter" in {
      val f = new Fixture(() => Promise[Unit]().future)
      val hung = f.route.shared()
      f.advance(HealthRoute.StaleAfter - 1.milli)
      f.route.shared() shouldBe theSameInstanceAs(hung)
      f.started.get() shouldBe 1
      f.advance(1.milli)
      f.route.shared() should not be theSameInstanceAs(hung)
      f.started.get() shouldBe 2
    }
  }

  "The health routes" should {
    "leave the Ponton routes and unknown paths as they were" in withServer(failing) { base =>
      call(HttpMethods.POST, s"$base/pontonxp/notification", HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<n/>"))._1 shouldBe StatusCodes.OK
      call(HttpMethods.GET, s"$base/pontonxp/message")._1 shouldBe StatusCodes.MethodNotAllowed
      call(HttpMethods.POST, s"$base/unknown")._1 shouldBe StatusCodes.NotFound
      call(HttpMethods.POST, s"$base/healthz")._1 shouldBe StatusCodes.MethodNotAllowed
    }
  }
}
