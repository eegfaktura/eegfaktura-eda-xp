package at.energydash.testsupport

import io.moquette.broker.Server
import io.moquette.broker.config.{ClasspathResourceLoader, ResourceLoaderConfig}

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Embedded MQTT broker (moquette) on the port of `application-test.conf` (18831), loopback, no persistence
 * (`src/test/resources/moquette.conf`). Started and stopped per test or per spec; never writes `data/`.
 */
object TestBroker {
  val Port = 18831
  val Url = s"tcp://127.0.0.1:$Port"

  def start(): Server = {
    java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/test-storage/moquette"))
    val server = new Server()
    server.startServer(new ResourceLoaderConfig(new ClasspathResourceLoader("moquette.conf")))
    server
  }

  /** How long a stop may take before the test goes on without it. */
  val StopTimeoutMillis = 15000L

  def withBroker[T](f: Server => T): T = {
    val server = start()
    try f(server) finally stop(server)
  }

  /**
   * Stops the broker. Clients of earlier tests and specs that are still connected (or reconnect to every new
   * broker on the fixed port) kept moquette's `stopServer` waiting for Netty without a limit — on the GitHub
   * runner the suite hung until the job timeout (33 open connections on the port, thread dump in
   * `TestBroker.stop`). So every connected client is disconnected and its session purged first; the stop itself
   * still waits at most [[StopTimeoutMillis]].
   */
  def stop(server: Server): Unit = {
    Try(server.listConnectedClients().asScala.toList).getOrElse(Nil).foreach { c =>
      Try(server.disconnectAndPurgeClientState(c.getClientID))
    }
    val stopper = new Thread(() => server.stopServer(), "test-broker-stop")
    stopper.setDaemon(true)
    stopper.start()
    stopper.join(StopTimeoutMillis)
    if (stopper.isAlive)
      System.err.println(s"TestBroker: stopServer did not finish within $StopTimeoutMillis ms; the test goes on")
  }
}
