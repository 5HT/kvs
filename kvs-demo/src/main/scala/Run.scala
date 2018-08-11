package mws.kvs

import scala.util.Try

import akka.actor.ActorSystem

object Run extends App {
  val config = """
    akka {
      loglevel = INFO
      remote {
        netty.tcp {
          hostname = 127.0.0.1
          port = 4281
        }
      }
      cluster {
        seed-nodes = ["akka.tcp://kvs@127.0.0.1:4281"]
      }
    }
  """

  import com.typesafe.config.ConfigFactory
  val cfg = ConfigFactory.parseString(config).withFallback(ConfigFactory.load()).resolve()
  implicit val system = ActorSystem("kvs", cfg)
  val kvs = Kvs(system)

  sys.addShutdownHook {
    system.terminate()
    import scala.concurrent.Await
    import scala.concurrent.duration.Duration
    Try(Await.result(system.whenTerminated,Duration.Inf))
  }

  kvs.onReady{
    val r = kvs.put("a","b")
    system.log.info(s"${r}")
  }
}
