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

  def withBroker[T](f: Server => T): T = {
    val server = start()
    try f(server) finally server.stopServer()
  }
}
