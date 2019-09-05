package zd.kvs
package en

import zd.kvs.store.Dba
import zd.gs.z._
import zd.proto.api.{N, MessageCodec, encode, decode}
import zd.proto.macrosapi.{caseCodecAuto}
import scala.util.Try

final case class Fd
  ( @N(1) id: String
  , @N(2) top: Option[String]=None
  , @N(3) length: Long=0
  , @N(4) nextid: Long=1
  )

object FdHandler {
  private implicit val codec: MessageCodec[Fd] = caseCodecAuto[Fd]
  private def pickle(e: Fd): Res[Array[Byte]] = encode[Fd](e).right
  private def unpickle(a: Array[Byte]): Res[Fd] = Try(decode[Fd](a)).fold(Throwed(_).left, _.right)

  def put(el: Fd)(implicit dba: Dba): Res[Unit] = pickle(el).flatMap(x => dba.put(el.id,x))
  def get(el: Fd)(implicit dba: Dba): Res[Option[Fd]] = dba.get(el.id) match {
    case Right(Some(x)) => unpickle(x).map(_.just)
    case Right(None) => Right(None)
    case x@Left(_) => x.coerceRight
  }
  def length(id: String)(implicit dba: Dba): Res[Long] = get(Fd(id)).map(_.map(_.length).getOrElse(0L))
  def delete(el: Fd)(implicit dba: Dba): Res[Unit] = dba.delete(el.id)
}
