package kvs.search

import kvs.rng.{Dba, AckReceiverErr}
import scala.util.Try
import zio.*

class DbaEff(dba: Dba.Service):
  type K = String
  type V = Array[Byte]
  type Err = AckReceiverErr | Throwable
  type R[A] = Either[Err, A]

  def put(key: K, value: V): R[Unit] = run(dba.put(stob(key), value))
  def get(key: K): R[Option[V]] = run(dba.get(stob(key)))
  def delete(key: K): R[Unit] = run(dba.delete(stob(key)))

  private def run[A](eff: IO[Err, A]): R[A] =
    Try(Runtime.default.unsafeRun(eff.either)).toEither.flatten

  private inline def stob(s: String): Array[Byte] =
    s.getBytes("utf8").nn
