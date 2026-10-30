package at.energydash.actors.http

import com.typesafe.config.{Config => TypesafeConfig}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.pattern.after
import org.slf4j.{Logger, LoggerFactory}

import java.sql.{DriverManager, SQLException}
import java.util.Properties
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try, Using}

/**
 * Kubernetes probes, unauthenticated (like every route on this server) and without a body that names
 * anything: `GET /healthz` answers `ok` without checking a dependency, so a database outage never
 * restarts the pod; `GET /readyz` answers `ok` when `ready` succeeds within `readyTimeout`, otherwise
 * 503 `unavailable` and the reason in the log (warn, on a change and at most once per `HealthRoute.WarnEvery`
 * while it lasts, so callers of the open port cannot flood the log).
 * At most one `ready` runs at a time: concurrent calls, and calls within `reuseFor` of its start, share
 * its result, so callers of the open port cannot multiply the load on the database. A check still
 * running after `staleAfter` counts as failed and the next call starts a new one.
 * `now` (nanoseconds) is the clock for all three; tests replace it.
 */
class HealthRoute(ready: () => Future[Unit],
                  readyTimeout: FiniteDuration = HealthRoute.ReadyTimeout,
                  reuseFor: FiniteDuration = HealthRoute.ReuseFor,
                  staleAfter: FiniteDuration = HealthRoute.StaleAfter,
                  now: () => Long = () => System.nanoTime())(implicit val system: ActorSystem[_]) {

  private implicit val ec: ExecutionContext = system.executionContext
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  /** The last check and when it started. */
  private val last = new AtomicReference[(Long, Future[Unit])]((now() - staleAfter.toNanos, Future.failed(new IllegalStateException("not checked yet"))))

  private def text(status: StatusCode, body: String) = HttpResponse(status, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, body))

  /** The running or recent check, or a new one. */
  private[energydash] def shared(): Future[Unit] = {
    val at = now()
    val current = last.get()
    val age = at - current._1
    val reusable = if (current._2.isCompleted) age < reuseFor.toNanos else age < staleAfter.toNanos
    if (reusable) current._2
    else {
      val result = Promise[Unit]()
      if (last.compareAndSet(current, (at, result.future))) {
        if (!current._2.isCompleted) record(Failure(new TimeoutException(s"database check still running after $staleAfter")))
        result.completeWith(Try(ready()).fold(Future.failed, identity))
        result.future.onComplete(record)
        result.future
      } else last.get()._2
    }
  }

  // only the answer is bounded here; the check itself ends by its own (driver) timeouts
  private def check(): Future[Unit] = {
    val timedOut = after(readyTimeout)(Future.failed(new TimeoutException(s"no answer within $readyTimeout")))
    Future.firstCompletedOf(Seq(shared(), timedOut))
  }

  private var wasReady = true
  private var lastWarn = 0L

  /** Logged per check, not per call: a failure when it starts and at most once per WarnEvery while it lasts, the recovery once. */
  private def record(result: Try[Unit]): Unit = synchronized {
    result match {
      case Success(_) =>
        if (!wasReady) logger.info("readyz: database check succeeds again")
        wasReady = true
      case Failure(ex) =>
        val at = now()
        if (wasReady || at - lastWarn >= HealthRoute.WarnEvery.toNanos) {
          logger.warn(s"readyz: not ready, database check failed: ${HealthRoute.describe(ex)}")
          lastWarn = at
        }
        wasReady = false
    }
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
          case Failure(_) => complete(text(StatusCodes.ServiceUnavailable, "unavailable"))
        }
      }
    }
  )
}

object HealthRoute {
  val ReadyTimeout: FiniteDuration = 2.seconds
  val ReuseFor: FiniteDuration = 1.second
  val StaleAfter: FiniteDuration = 10.seconds
  val WarnEvery: FiniteDuration = 1.minute
  /** pgjdbc connect, login and socket timeout and the statement's query timeout, in seconds. */
  val DriverTimeoutSeconds = 2
  /** Postgres too_many_connections. */
  val TooManyConnections = "53300"

  private val logger: Logger = LoggerFactory.getLogger(classOf[HealthRoute])

  /** Class and message, and the cause's (pgjdbc puts the real reason there). */
  def describe(ex: Throwable): String =
    s"${ex.getClass.getName}: ${ex.getMessage}" + Option(ex.getCause).fold("")(c => s" (cause ${c.getClass.getName}: ${c.getMessage})")

  /**
   * `SELECT 1` over a connection of its own, opened for the check and closed after it — not the
   * service's pools (`slick.pgsql.local` settings: url, user, password), so a busy pool never makes the
   * service unready and the check never takes a connection from it. The driver's timeouts end it
   * (connect and login 2 s, then the query 2 s: about 4 s at worst). `ec` runs the blocking JDBC call.
   * Postgres refusing the connection as one too many (SQLSTATE 53300) counts as ready, logged at warn at
   * most once per `WarnEvery`: the service's pools still have their connections, and a saturated database
   * must not take every pod out of its Service.
   */
  def databaseCheck(config: TypesafeConfig)(implicit ec: ExecutionContext): () => Future[Unit] = {
    val db = config.getConfig("slick.pgsql.local.db")
    val url = db.getString("url")
    val props = new Properties()
    Seq("user", "password").filter(db.hasPath).foreach(k => props.setProperty(k, db.getString(k)))
    Seq("connectTimeout", "loginTimeout", "socketTimeout").foreach(props.setProperty(_, DriverTimeoutSeconds.toString))
    val lastWarn = new AtomicLong(System.nanoTime() - WarnEvery.toNanos)
    () => Future {
      try Using.resource(DriverManager.getConnection(url, props)) { c =>
        Using.resource(c.createStatement()) { st =>
          st.setQueryTimeout(DriverTimeoutSeconds)
          Using.resource(st.executeQuery("SELECT 1"))(rs => if (!rs.next()) throw new IllegalStateException("SELECT 1 returned no row"))
        }
      } catch {
        case ex: SQLException if ex.getSQLState == TooManyConnections =>
          val at = System.nanoTime()
          val previous = lastWarn.get()
          if (at - previous >= WarnEvery.toNanos && lastWarn.compareAndSet(previous, at))
            logger.warn(s"readyz: database refuses further connections (SQLSTATE $TooManyConnections), counted as ready: ${describe(ex)}")
      }
    }
  }
}
