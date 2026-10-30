package at.energydash.actors.http

import com.typesafe.config.{Config => TypesafeConfig}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.pattern.after
import org.slf4j.{Logger, LoggerFactory}

import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import scala.util.{Failure, Success, Try, Using}

/**
 * Kubernetes probes, unauthenticated (like every route on this server) and without a body that names
 * anything: `GET /healthz` answers `ok` without checking a dependency, so a database outage never
 * restarts the pod; `GET /readyz` answers `ok` when `ready` succeeds within `readyTimeout`, otherwise
 * 503 `unavailable` and the reason in the log (warn).
 * At most one `ready` runs at a time: concurrent calls, and calls within `reuseFor` of its start, share
 * its result, so callers of the open port cannot multiply the load on the database.
 */
class HealthRoute(ready: () => Future[Unit],
                  readyTimeout: FiniteDuration = HealthRoute.ReadyTimeout,
                  reuseFor: FiniteDuration = HealthRoute.ReuseFor)(implicit val system: ActorSystem[_]) {

  private implicit val ec: ExecutionContext = system.executionContext
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  /** The last check and when it started (System.nanoTime). */
  private val last = new AtomicReference[(Long, Future[Unit])]((System.nanoTime() - reuseFor.toNanos, Future.failed(new IllegalStateException("not checked yet"))))

  private def text(status: StatusCode, body: String) = HttpResponse(status, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, body))

  private def shared(): Future[Unit] = {
    val now = System.nanoTime()
    val current = last.get()
    if (!current._2.isCompleted || now - current._1 < reuseFor.toNanos) current._2
    else {
      val result = Promise[Unit]()
      if (last.compareAndSet(current, (now, result.future))) {
        result.completeWith(Try(ready()).fold(Future.failed, identity))
        result.future
      } else last.get()._2
    }
  }

  // only the answer is bounded here; the check itself ends by its own (driver) timeouts
  private def check(): Future[Unit] = {
    val timedOut = after(readyTimeout)(Future.failed(new TimeoutException(s"no answer within $readyTimeout")))
    Future.firstCompletedOf(Seq(shared(), timedOut))
  }

  // path first, method inside: a POST to an unknown path must stay a 404, not become a 405
  val healthRoutes: Route = concat(
    path("healthz") {
      get(complete(text(StatusCodes.OK, "ok")))
    },
    path("readyz") {
      get {
        onComplete(check()) {
          case Success(_) => complete(text(StatusCodes.OK, "ok"))
          case Failure(ex) =>
            logger.warn(s"readyz: not ready, database check failed: ${ex.getMessage}")
            complete(text(StatusCodes.ServiceUnavailable, "unavailable"))
        }
      }
    }
  )
}

object HealthRoute {
  val ReadyTimeout: FiniteDuration = 2.seconds
  val ReuseFor: FiniteDuration = 1.second
  /** pgjdbc connect, login and socket timeout and the statement's query timeout, in seconds. */
  val DriverTimeoutSeconds = 2

  /**
   * `SELECT 1` over a connection of its own, opened for the check and closed after it — not the
   * service's pools (`slick.pgsql.local` settings: url, user, password), so a busy pool never makes the
   * service unready and the check never takes a connection from it. The driver's timeouts end it.
   */
  def databaseCheck(config: TypesafeConfig)(implicit ec: ExecutionContext): () => Future[Unit] = {
    val db = config.getConfig("slick.pgsql.local.db")
    val url = db.getString("url")
    val props = new Properties()
    Seq("user", "password").filter(db.hasPath).foreach(k => props.setProperty(k, db.getString(k)))
    Seq("connectTimeout", "loginTimeout", "socketTimeout").foreach(props.setProperty(_, DriverTimeoutSeconds.toString))
    () => Future {
      blocking {
        Using.resource(DriverManager.getConnection(url, props)) { c =>
          Using.resource(c.createStatement()) { st =>
            st.setQueryTimeout(DriverTimeoutSeconds)
            Using.resource(st.executeQuery("SELECT 1"))(rs => if (!rs.next()) throw new IllegalStateException("SELECT 1 returned no row"))
          }
        }
      }
    }
  }
}
