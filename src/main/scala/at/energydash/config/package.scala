package at.energydash

import com.typesafe.config.{Config, ConfigFactory}

package object config {
  implicit class ConfigExtensions(self: Config) {

    def get[T](key:String,construct:String => T):T = construct(self.getString(key))

    def getConfigured[T](key: String, constructor: Config => T): T = constructor(self.getConfig(key))

    def getOption[T](key: String, extract: Config => (String => T)): Option[T] = {
      if (self.hasPath(key)) Option(extract(self)(key))
      else None
    }

    def getOptionConfigured[T](key: String, constructor: Config => T): Option[T] = {
      getOption(key, _.getConfig).map(constructor)
    }

    def getOptionConfiguredIf[T](key:String,constructor: Config => T,createFlag:String = "create"):Option[T] = {
      if(self.getBoolean(s"$key.$createFlag")) self.getOptionConfigured(key,constructor)
      else None
    }

    def getConfigOrEmpty(key:String): Config = {
      if (self.hasPath(key))
        self.getConfig(key)
      else
        ConfigFactory.empty()
    }
  }
}
