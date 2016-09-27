package mws.rng

import java.lang.management.ManagementFactory
import javax.management.{InstanceAlreadyExistsException, InstanceNotFoundException, ObjectName, StandardMBean}
import scala.language.postfixOps
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.event.LoggingAdapter
import akka.util.{ByteString, Timeout}

/** JMX cient */
trait HashRingMBean {
  def get(key:String): String
  def put(key:String, data: String):String
  def delete(key:String):Unit
}

private[mws] class HashRingJmx(ring:HashRing, log: LoggingAdapter) {
  private val server = ManagementFactory.getPlatformMBeanServer
  private val name = new ObjectName("akka:type=Store")
  implicit val timeout = Timeout(3 seconds)

  def createMBean() = {
    val mbean = new StandardMBean(classOf[HashRingMBean]) with HashRingMBean {

      def get(key: String) = {
        val value = Await.result(ring.get(key), timeout.duration)
        log.info(s"val for $key -> $value")
        value match {
          case Some(byteStr) => new String(byteStr.toArray)
          case None => "not_present"
        }
      }

      def put(key: String, value: String): String = {
        Await.result(ring.put(key, ByteString(value)),timeout.duration) match {
          case AckSuccess => "ok"
          case AckQuorumFailed => "quorum failed"
          case AckTimeoutFailed => "failed by timeout"
        }
      }
      def delete(key:String) = {
        Await.result(ring.delete(key),timeout.duration)match {
          case res => log.info(s"del rez for k=$key is $res")
        }
      }

    }

    try {
      server.registerMBean(mbean, name)
      log.info("Registered hash ring JMX MBean [{}]", name)
    } catch {
      case e: InstanceAlreadyExistsException =>
    }
  }

  def unregisterMBean(): Unit = {
    try {
      server.unregisterMBean(name)
    } catch {
      case e: InstanceNotFoundException =>
    }
  }
}