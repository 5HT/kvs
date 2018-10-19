package mws.kvs
package handle

import scala.util.Try

import scalaz._, Scalaz._

import akka.actor.ActorSystem
import akka.testkit._
import org.scalatest._

object EnHandlerTest {
  val fid = "fid" + java.util.UUID.randomUUID.toString
  type EnType = En[FeedEntry]

  final case class FeedEntry(string:String,twoDimVector:Vector[Vector[(String,String)]],anotherVector:Vector[String])

  implicit object feedHandler extends FdHandler {
    import scala.pickling._,Defaults._,binary._
    def pickle(e:Fd):Array[Byte] = e.pickle.value
    def unpickle(a:Array[Byte]):Res[Fd] = Try(a.unpickle[Fd]).toDisjunction.leftMap(UnpickleFailed(_))
  }

  implicit object FeedEntryEnHandler extends EnHandler[FeedEntry] {
    val fh = feedHandler
    import scala.pickling._,Defaults._,binary._
    def pickle(e: En[FeedEntry]): Array[Byte] = e.pickle.value
    def unpickle(a: Array[Byte]): Res[En[FeedEntry]] = Try(a.unpickle[En[FeedEntry]]).toDisjunction.leftMap(UnpickleFailed(_))
  }
}

class EnHandlerTest extends TestKit(ActorSystem("Test"))
  with FreeSpecLike with Matchers with EitherValues with BeforeAndAfterAll {

  import EnHandlerTest._

  val kvs = Kvs(system)

  Thread.sleep(2000)

  val mod = 50
  def entry(n:Int):EnType = En(fid,data=FeedEntry(s"string$n", Vector.fill(n % mod,n % mod)((s"string$n",s"string$n")), Vector.fill(n % mod)(s"string$n")))

  val e1 = entry(1)
  val e2 = entry(2)
  val e3 = entry(3)
  val e5 = entry(5)

  override def afterAll = TestKit.shutdownActorSystem(system)

  "Feed should" - {
    "be empty at creation" in {
      kvs.stream[EnType](fid) shouldBe (-\/(FeedNotExists(fid)))
    }

    "should save e1" in {
      val saved = kvs.add(e1).toEither.right.get
      kvs.get(Fd(fid)).map(_.count) match {
        case \/-(x) => x shouldBe 1
        case -\/(RngThrow(t)) => t.printStackTrace
        case -\/(x) => fail(x.toString)
      }
      (saved.fid, saved.id, saved.data) shouldBe(e1.fid, "1", e1.data)
    }

    "should save e2" in {
      val saved = kvs.add(e2).toEither.right.get
      kvs.get(Fd(fid)).toEither.right.get.count shouldBe 2
      (saved.fid, saved.id, saved.data) shouldBe(e2.fid, "2", e2.data)
    }

    "should get e1 and e2 from feed" in {
      kvs.get(Fd(fid)).toEither.right.get.count shouldBe 2

      val stream = kvs.stream[EnType](fid)
      stream.map(_.toList) shouldBe List(e2.copy(id="2",prev="1").right, e1.copy(id="1").right).right
    }

    "should save entry(3)" in {
      val saved = kvs.add(e3).toEither.right.get
      (saved.fid, saved.id, saved.data) shouldBe(e3.fid, "3", e3.data)
    }

    "should not save entry(2) again" in {
      kvs.add(e2.copy(id="2")).toEither.left.value shouldBe EntryExist(s"${fid}.2")
    }

    "should get 3 values from feed" in {
      kvs.get(Fd(fid)).toEither.right.get.count shouldBe 3

      val stream = kvs.stream[EnType](fid)
      stream.map(_.toList) shouldBe List(e3.copy(id="3",prev="2").right, e2.copy(id="2",prev="1").right, e1.copy(id="1").right).right
    }

    "should not remove unexisting entry from feed" in {
      kvs.remove(fid,"5").toEither.left.value shouldBe NotFound(s"${fid}.5")
    }

    "should remove entry(2) from feed without prev/next/data" in {
      val deleted = kvs.remove(e2.fid,"2").toEither.right.get

      (deleted.fid, deleted.id, deleted.data) shouldBe(e2.fid, "2", e2.data)
    }

    "should get 2 values from feed" in {
      kvs.get(Fd(fid)).toEither.right.get.count shouldBe 2

      val stream = kvs.stream[EnType](fid)
      stream.map(_.toList) shouldBe List(e3.copy(id="3",prev="1").right, e1.copy(id="1").right).right
    }

    "should remove entry(1) from feed" in {
      val deleted = kvs.remove(fid,"1").toEither.right.get

      (deleted.fid, deleted.id, deleted.data) shouldBe(e1.fid, "1", e1.data)
    }

    "should get 1 values from feed" in {
      kvs.get(Fd(fid)).toEither.right.get.count shouldBe 1

      val stream = kvs.stream[EnType](fid)
      stream.map(_.toList) shouldBe List(e3.copy(id="3").right).right
    }

    "should remove entry(3) from feed" in {
      val deleted = kvs.remove(fid,"3").toEither.right.get

      (deleted.fid, deleted.id, deleted.data) shouldBe(e3.fid, "3", e3.data)
    }

    "should be empty" in {
      kvs.get(Fd(fid)).toEither.right.get.count shouldBe 0
      kvs.stream[EnType](fid).toEither.right.get shouldBe empty
    }

    "should not create stack overflow" in {
      val limit = 100

      Stream.from(1,1).takeWhile( _.<=(limit)).foreach{ n =>
        val toadd = entry(n)
        val added = kvs.add(toadd).toEither.right.get
        (added.fid, added.id, added.data) shouldBe (toadd.fid, (n+3).toString, toadd.data)
      }

      Stream.from(1,1).takeWhile( _.<=(limit)).foreach{ n =>

        val toremove = entry(n).copy(id=(n+3).toString)
        val removed = kvs.remove(toremove.fid, toremove.id).toEither.right.get

        (removed.fid, removed.id, removed.data) shouldBe (toremove.fid, (n+3).toString, toremove.data)

        kvs.get(Fd(fid)).toEither.right.get.count shouldBe (limit - n)
      }
    }

    "feed should be empty at the end test" in {
      kvs.get(Fd(fid)).toEither.right.get.count shouldBe 0
      kvs.stream[EnType](fid).toEither.right.value shouldBe empty
      kvs.delete(Fd(fid))
      kvs.stream[EnType](fid) shouldBe ('left)
    }
  }
}
