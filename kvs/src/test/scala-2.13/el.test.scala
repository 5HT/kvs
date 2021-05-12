package zd.kvs

import akka.actor._
import com.typesafe.config.{ConfigFactory}
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

class ElHandlerTest extends AnyFreeSpecLike with Matchers with EitherValues with BeforeAndAfterAll {
  val kvs = Kvs.mem()

  "el handler should" - {
    "return error when element is absent" in {
      kvs.el.get[String]("key").getOrElse(???) should be (None)
    }
    "save value" in {
      kvs.el.put("key","value").getOrElse(???) should be ("value")
    }
    "retrieve value" in {
      kvs.el.get[String]("key").getOrElse(???) should be (Some("value"))
    }
    "override value" in {
      kvs.el.put("key","value2").getOrElse(???) should be ("value2")
    }
    "delete value" in {
      kvs.el.delete[String]("key").getOrElse(???) should be (())
    }
    "clean up" in {
      kvs.el.get[String]("key").getOrElse(???) should be (None)
    }
  }
}