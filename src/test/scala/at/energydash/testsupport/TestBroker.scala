package at.energydash.testsupport

import io.moquette.broker.Server
import io.moquette.broker.config.{ClasspathResourceLoader, ResourceLoaderConfig}

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
   * Stops the broker, but waits at most [[StopTimeoutMillis]]. moquette's `stopServer` waits without a limit
   * for Netty to close; with a client of the test still connected (a Paho disconnect in flight), both can
   * wait for each other — on the GitHub runner this hung the whole suite until the job timeout.
   */
  def stop(server: Server): Unit = {
    val stopper = new Thread(() => server.stopServer(), "test-broker-stop")
    stopper.setDaemon(true)
    stopper.start()
    stopper.join(StopTimeoutMillis)
    if (stopper.isAlive)
      System.err.println(s"TestBroker: stopServer did not finish within $StopTimeoutMillis ms; the test goes on")
  }
}
