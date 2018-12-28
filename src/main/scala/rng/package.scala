package mws

import akka.actor.Address
import akka.cluster.VectorClock
import com.google.protobuf.{ByteString, ByteStringWrap}
import mws.rng.data.{Data, Vec}
import mws.rng.msg.{StorePutStatus}
import scala.annotation.tailrec
import scala.collection.immutable.{TreeMap}
import scalaz._

package object rng {
  type Bucket = Int
  type VNode = Int
  type Node = Address
  type Key = ByteString
  type Value = ByteString
  type VectorClockList = Seq[Vec]
  type Age = (VectorClock, Long)
  type PreferenceList = Set[Node]

  sealed trait Ack
  final case object AckSuccess extends Ack
  final case object AckQuorumFailed extends Ack
  final case object AckTimeoutFailed extends Ack

  sealed trait FsmState
  final case object ReadyCollect extends FsmState
  final case object Collecting extends FsmState
  final case object Sent extends FsmState

  sealed trait FsmData
  final case class Statuses(all: List[StorePutStatus]) extends FsmData
  final case class DataCollection(perNode: Seq[(Option[Data], Node)], nodes: Int) extends FsmData
  
  final case object OpsTimeout

  def makevc(l: VectorClockList): VectorClock = new VectorClock(TreeMap.empty[String, Long] ++ l.map(a => a.key -> a.value))
  def fromvc(vc: VectorClock): VectorClockList = vc.versions.toSeq.map(a => Vec(a._1, a._2))

  def stob(s: String): ByteString = ByteString.copyFrom(s, "UTF-8")
  def itoa(v: Int): Array[Byte] = Array[Byte]((v >> 24).toByte, (v >> 16).toByte, (v >> 8).toByte, v.toByte)
  def itob(v: Int): ByteString = ByteStringWrap.wrap(Array[Byte]((v >> 24).toByte, (v >> 16).toByte, (v >> 8).toByte, v.toByte))
  def atob(a: Array[Byte]): ByteString = ByteStringWrap.wrap(a)

  /* returns (actual data, list of outdated nodes) */
  def order[E](l: Seq[E], age: E => Age): (Option[E], Seq[E]) = {
    @tailrec
    def itr(l: Seq[E], newest: E): E = l match {
      case xs if xs.isEmpty => newest
      case h +: t if t.exists(age(h)._1 < age(_)._1) => itr(t, newest )
      case h +: t if age(h)._1 > age(newest)._1 => itr(t, h)
      case h +: t if age(h)._1 <> age(newest)._1 &&
        age(h)._2 > age(newest)._2 => itr(t, h)
      case _ => itr(l.tail, newest)
    }

    (l map (age(_)._1)).toSet.size match {
      case 0 => (None, Nil)
      case 1 => (Some(l.head), Nil)
      case n =>
        val correct = itr(l.tail, l.head)
        (Some(correct), l.filterNot(age(_)._1 == age(correct)._1))
    }
  }

  implicit class StringExt(value: String) {
    def blue: String = s"\u001B[34m${value}\u001B[0m"
    def green: String = s"\u001B[32m${value}\u001B[0m"
  }

  implicit val ByteStringEqual: Equal[ByteString] = Equal.equalA

  implicit class ByteStringExt(value: ByteString) {
    def ++(x: ByteString): ByteString = value.concat(x)
  }

  def now_ms(): Long = System.currentTimeMillis

  implicit class VectorClockExt(old: VectorClock) {
    def <=(candidat: VectorClock): Boolean =
      old < candidat || old == candidat
  }
}
