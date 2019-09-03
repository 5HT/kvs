package zd.kvs

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.cluster.sharding._
import zd.kvs.store._
import zd.kvs.store.IdCounter
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Success}
import zd.kvs.en.{En, EnHandler, Fd, FdHandler}
import zd.kvs.el.ElHandler
import zd.kvs.file.{File, FileHandler}

/** Akka Extension to interact with KVS storage as built into Akka */
object Kvs extends ExtensionId[Kvs] with ExtensionIdProvider {
  override def lookup = Kvs
  override def createExtension(system: ExtendedActorSystem): Kvs = new Kvs(system)
}
class Kvs(system: ExtendedActorSystem) extends Extension {
  { /* start sharding */
    val sharding = ClusterSharding(system)
    val settings = ClusterShardingSettings(system)
    sharding.start(typeName=IdCounter.shardName,entityProps=IdCounter.props,settings=settings,
      extractEntityId = { case msg:String => (msg,msg) },
      extractShardId = { case msg:String => (math.abs(msg.hashCode) % 100).toString }
    )
  }

  val cfg = system.settings.config
  val store = cfg.getString("kvs.store")

  implicit val dba = system.dynamicAccess.createInstanceFor[Dba](store,
    List(classOf[ActorSystem]->system)).get

  if (cfg.getBoolean("akka.cluster.jmx.enabled")) {
    val jmx = new KvsJmx(this,system)
    jmx.createMBean()
    sys.addShutdownHook(jmx.unregisterMBean())
  }

  object el {
    def put[A: ElHandler](k: String,el: A): Res[A] = implicitly[ElHandler[A]].put(k,el)
    def get[A: ElHandler](k: String): Res[Option[A]] = implicitly[ElHandler[A]].get(k)
    def delete[A: ElHandler](k: String): Res[Unit] = implicitly[ElHandler[A]].delete(k)
  }

  object fd {
    def put(fd: Fd)(implicit fh: FdHandler): Res[Fd] = fh.put(fd)
    def get(fd: Fd)(implicit fh: FdHandler): Res[Option[Fd]] = fh.get(fd)
    def delete(fd: Fd)(implicit fh: FdHandler): Res[Unit] = fh.delete(fd)
  }

  def nextid(fid: String): Res[String] = dba.nextid(fid)

  def add[H <: En](el: H)(implicit h: EnHandler[H]): Res[H] = h.add(el)
  def put[H <: En](el: H)(implicit h: EnHandler[H]): Res[H] = h.put(el)
  def all[H <: En](fid: String, from: Option[H] = None)(implicit h: EnHandler[H]): Res[LazyList[Res[H]]] = h.all(fid, from)
  def get[H <: En](fid: String, id: String)(implicit h: EnHandler[H]): Res[Option[H]] = h.get(fid, id)
  def remove[H <: En](fid: String, id: String)(implicit h: EnHandler[H]): Res[H] = h.remove(fid, id)

  object file {
    def create(dir: String, name: String)(implicit h: FileHandler): Res[File] = h.create(dir, name)
    def append(dir: String, name: String, chunk: Array[Byte])(implicit h: FileHandler): Res[File] = h.append(dir, name, chunk)
    def stream(dir: String, name: String)(implicit h: FileHandler): Res[LazyList[Res[Array[Byte]]]] = h.stream(dir, name)
    def size(dir: String, name: String)(implicit h: FileHandler): Res[Long] = h.size(dir, name)
    def delete(dir: String, name: String)(implicit h: FileHandler): Res[File] = h.delete(dir, name)
    def copy(dir: String, name: (String, String))(implicit h: FileHandler): Res[File] = h.copy(dir, name)
  }

  object dump {
    def save(path: String): Res[String] = dba.save(path)
    def load(path: String): Res[Any] = dba.load(path)
    def loadJava(path: String): Res[Any] = dba.loadJava(path)
    def iterate(path: String, f: (String, Array[Byte]) => Option[(String, Array[Byte])], afterIterate: () => Unit): Unit = {
      val _ = dba.iterate(path, f, afterIterate)
    }
  }

  def onReady: Future[Unit] = {
    import system.dispatcher
    import system.log
    val p = Promise[Unit]()
    def loop(): Unit = {
      val _ = system.scheduler.scheduleOnce(1 second){
        dba.isReady onComplete {
          case Success(true) =>
            log.info("KVS is ready")
            p.success(())
          case _ =>
            log.info("KVS isn't ready yet...")
            loop()
        }
      }
    }
    loop()
    p.future
  }

  def compact(): Unit = {
    dba.compact()
  }
}
