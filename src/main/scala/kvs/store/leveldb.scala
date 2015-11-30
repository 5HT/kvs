package mws.kvs
package store

import java.io.File

import scala.util.Try
import scala.concurrent.Future
import scala.language.implicitConversions

import org.fusesource.leveldbjni.JniDBFactory._
import org.iq80.leveldb._
import com.typesafe.config.Config

object Leveldb {
  implicit def toBytes(value: String): Array[Byte] = bytes(value)
  implicit def fromBytes(value: Array[Byte]): String = asString(value)
  implicit def toErr(e:DBException):Err = Dbe(msg=e.getMessage)
  implicit def toErr(e:NullPointerException):Err = Dbe(msg=e.getMessage)

  val not_found:Err = Dbe(msg="not_found")

  def apply(cfg:Config):Dba = new Leveldb(cfg)
}
class Leveldb(cfg:Config) extends Dba {
  import Leveldb._

  val leveldbOptions = new Options().createIfMissing(true)
  def leveldbReadOptions = new ReadOptions().verifyChecksums(cfg.checksum)
  val leveldbWriteOptions = new WriteOptions().sync(cfg.fsync).snapshot(false)
  val leveldb: DB = leveldbFactory.open(cfg.dir,
    if (cfg.native) leveldbOptions
    else leveldbOptions.compressionType(CompressionType.NONE))

  def leveldbFactory =
    if (cfg.native) org.fusesource.leveldbjni.JniDBFactory.factory
    else org.iq80.leveldb.impl.Iq80DBFactory.factory

  implicit class LeveldbConfig(config: Config) {
    def native: Boolean = sys.props.get("os.name") match {
      case Some(os) if os.startsWith("Windows") =>
        println("Windows has been detected. Forcing usage of Java port of LevelDB")
        false
      case _ => config.getBoolean("native")
    }
    def checksum: Boolean = config.getBoolean("checksum")
    def fsync: Boolean = config.getBoolean("fsync")
    def dir: File = new File(config.getString("dir"))
  }

  // KVS API

  def get(key:String) : Either[Err,Array[Byte]] = try {
    Option(leveldb.get(key)) match {
      case Some(v) => Right(v)
      case None => Left(not_found)
    }
  } catch {case t:DBException => Left(t)}

  def put(key:String,value:Array[Byte]) : Either[Err,Array[Byte]] = try {
    leveldb.put(key,value)
    Right(value)
  } catch {case t: DBException => Left(t)}

  def delete(key:String) : Either[Err,Array[Byte]] = get(key) match {
    case Right(v)=>leveldb.delete(key);Right(v)
    case Left(l) => Left(l)
  }

  def close(): Unit = Try(leveldb.close())
  def isReady: Future[Boolean] = Future.successful(true)
}