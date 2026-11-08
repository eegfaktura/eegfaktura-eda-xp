package at.energydash

import com.typesafe.config.Config

package object config {
  implicit class ConfigExtensions(self: Config) {

    def getOption[T](key: String, extract: Config => (String => T)): Option[T] = {
      if (self.hasPath(key)) Option(extract(self)(key))
      else None
    }

    def getOptionConfigured[T](key: String, constructor: Config => T): Option[T] = {
      getOption(key, _.getConfig).map(constructor)
    }

    def optionalString(path: String): Option[String] = if (self.hasPath(path)) {
      Some(self.getString(path))
    } else {
      None
    }
  }
}
