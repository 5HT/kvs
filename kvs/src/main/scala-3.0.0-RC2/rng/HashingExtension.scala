package zd.rng

import akka.actor.*
import com.typesafe.config.Config
import java.security.MessageDigest
import scala.annotation.tailrec
import scala.collection.{SortedMap}

class HashingImpl(config: Config) extends Extension {
  val hashLen = config.getInt("hash-length")
  val bucketsNum = config.getInt("buckets")
  val bucketRange = (math.pow(2, hashLen.toDouble) / bucketsNum).ceil.toInt

  def hash(word: Array[Byte]): Int = {
    implicit val digester: MessageDigest = MessageDigest.getInstance("MD5").nn
    digester `update` word
    val digest: Array[Byte] = digester.digest.nn

    (0 to hashLen / 8 - 1).foldLeft(0)((acc, i) =>
      acc | ((digest(i) & 0xff) << (8 * (hashLen / 8 - 1 - i)))
    ) //take first 4 byte
  }

  def findBucket(key: Key): Bucket = (hash(key) / bucketRange).abs

  def findNodes(hashKey: Int, vNodes: SortedMap[Bucket, Address], nodesNumber: Int): PreferenceList = {
    @tailrec
    def findBucketNodes(hashK: Int, nodes: PreferenceList): PreferenceList = {
      val it = vNodes.keysIteratorFrom(hashK)
      val hashedNode = if (it.hasNext) it.next() else vNodes.firstKey
      val node = vNodes(hashedNode)
      val prefList = nodes + node
      prefList.size match {
        case `nodesNumber` => prefList
        case _ => findBucketNodes(hashedNode + 1, prefList)
      }
    }

    findBucketNodes(hashKey, Set.empty[Node])
  }
}

object HashingExtension extends ExtensionId[HashingImpl] with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem): HashingImpl =
    new HashingImpl(system.settings.config.getConfig("ring").nn)

  override def lookup(): HashingExtension.type = HashingExtension
}