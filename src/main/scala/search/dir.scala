package kvs
package search

import java.io.{IOException, ByteArrayOutputStream}
import java.nio.file.{NoSuchFileException, FileAlreadyExistsException}
import java.util.{Collection, Collections, Arrays}
import java.util.concurrent.atomic.AtomicLong
import org.apache.lucene.store._
import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import zero.ext._, either._, traverse._
import zd.proto.Bytes

import file.FileHandler, store.Dba

class KvsDirectory(dir: FdKey)(implicit dba: Dba) extends BaseDirectory(new KvsLockFactory(dir)) {
  val flh = new FileHandler {
    override val chunkLength = 10 * 1000 * 1000 // 10 MB
  }
  val enh = feed
  val fdh = feed.meta

  private[this] val outs = TrieMap.empty[String,ByteArrayOutputStream]
  private[this] val nextTempFileCounter = new AtomicLong

  def exists(): Res[Boolean] = {
    fdh.get(dir).map(_.isDefined)
  }
  
  def deleteAll(): Res[Unit] = {
    for {
      xs <- enh.all(dir)
      ys <- xs.sequence
      _ <- ys.map{ case (key, _) =>
        val name = key
        val path = PathKey(dir, name)
        for {
          _ <- flh.delete(path).void.recover{ case _: FileNotExists => () }
          _ <- enh.remove(EnKey(dir, name))
        } yield ()
      }.sequence_
      _ <- enh.cleanup(dir)
      _ <- fdh.delete(dir)
    } yield ()
  }

  /**
   * Returns names of all files stored in this directory.
   * The output must be in sorted (UTF-16, java's {//link String#compareTo}) order.
   * 
   * //throws IOException in case of I/O error
   */
  override
  def listAll(): Array[String] = {
    ensureOpen()
    enh.all(dir).flatMap(_.sequence).fold(
      l => throw new IOException(l.toString)
    , r => r.map(x => x._1.bytes.mkString).sorted.toArray
    )
  }

  /**
   * Removes an existing file in the directory.
   *
   * This method must throw {//link NoSuchFileException}
   * if {@code name} points to a non-existing file.
   *
   * @param name the name of an existing file.
   * //throws IOException in case of I/O error
   */
  override
  def deleteFile(name: String): Unit = {
    val name1 = ElKeyExt.from_str(name)
    sync(Collections.singletonList(name))
    val res: Res[Boolean] = for {
      _ <- flh.delete(PathKey(dir, name1))
      x <- enh.remove(EnKey(dir, name1))
      _ <- enh.cleanup(dir)
    } yield x
    res.fold(
      l => l match {
        case _: _root_.kvs.FileNotExists => throw new NoSuchFileException(s"${dir}/${name}")
        case x => throw new IOException(x.toString)
      },
      r => r match {
        case true => ()
        case false => throw new NoSuchFileException(s"${dir}/${name}")
      }
    )
  }

  /**
   * Returns the byte length of a file in the directory.
   *
   * This method must throw {//link NoSuchFileException}
   * if {@code name} points to a non-existing file.
   *
   * @param name the name of an existing file.
   * //throws IOException in case of I/O error
   */
  override
  def fileLength(name: String): Long = {
    ensureOpen()
    val name1 = ElKeyExt.from_str(name)
    sync(Collections.singletonList(name))
    flh.size(PathKey(dir, name1)).fold(
      l => l match {
        case _: _root_.kvs.FileNotExists => throw new NoSuchFileException(s"${dir}/${name}")
        case _ => throw new IOException(l.toString)
      },
      r => r
    )
  }

  /**
   * Creates a new, empty file in the directory and returns an {//link IndexOutput}
   * instance for appending data to this file.
   *
   * This method must throw {//link java.nio.file.FileAlreadyExistsException} if the file
   * already exists.
   *
   * @param name the name of the file to create.
   * //throws IOException in case of I/O error
   */
  override
  def createOutput(name: String, context: IOContext): IndexOutput = {
    ensureOpen()
    val name1 = ElKeyExt.from_str(name)
    val r = for {
      _ <- enh.prepend(EnKey(dir, name1), Bytes.empty)
      _ <- flh.create(PathKey(dir, name1))
    } yield ()
    r.fold(
      l => l match {
        case _: _root_.kvs.EntryExists => throw new FileAlreadyExistsException(s"${dir}/${name}")
        case _: _root_.kvs.FileAlreadyExists => throw new FileAlreadyExistsException(s"${dir}/${name}")
        case _ => throw new IOException(l.toString)
      },
      _ => {
        val out = new ByteArrayOutputStream;
        outs += ((name, out))
        new OutputStreamIndexOutput(s"${dir}/${name}", name, out, 8192)
      }
    )
  }

  /**
   * Creates a new, empty, temporary file in the directory and returns an {//link IndexOutput}
   * instance for appending data to this file.
   *
   * The temporary file name (accessible via {//link IndexOutput#getName()}) will start with
   * {@code prefix}, end with {@code suffix} and have a reserved file extension {@code .tmp}.
   */
  override
  def createTempOutput(prefix: String, suffix: String, context: IOContext): IndexOutput = {
    ensureOpen()
    @tailrec def loop(): Res[String] = {
      val name = Directory.getTempFileName(prefix, suffix, nextTempFileCounter.getAndIncrement)
      val name1 = ElKeyExt.from_str(name)
      val res = for {
        _ <- enh.prepend(EnKey(dir, name1), Bytes.empty)
        _ <- flh.create(PathKey(dir, name1))
      } yield name
      res match {
        case Left(_: EntryExists) => loop()
        case Left(_: FileAlreadyExists) => loop()
        case x => x
      }
    }
    val res = loop()
    res.fold(
      l => throw new IOException(l.toString),
      name => {
        val out = new ByteArrayOutputStream;
        outs += ((name, out))
        new OutputStreamIndexOutput(s"$dir/$name", name, out, 8192)
      }
    )
  }

  /**
   * Ensures that any writes to these files are moved to
   * stable storage (made durable).
   *
   * Lucene uses this to properly commit changes to the index, to prevent a machine/OS crash
   * from corrupting the index.
   */
  override
  def sync(names: Collection[String]): Unit = {
    ensureOpen()
    names.stream.forEach{ name =>
      outs.get(name).map(x => Bytes.unsafeWrap(x.toByteArray)).foreach{ xs =>
        val name1 = ElKeyExt.from_str(name)
        flh.append(PathKey(dir, name1), xs).fold(
          l => throw new IOException(l.toString),
          _ => ()
        )
        outs -= name
      }
    }
  }

  override
  def syncMetaData(): Unit = {
    ensureOpen()
    ()
  }

  /**
   * Renames {@code source} file to {@code dest} file where
   * {@code dest} must not already exist in the directory.
   *
   * It is permitted for this operation to not be truly atomic, for example
   * both {@code source} and {@code dest} can be visible temporarily in {//link #listAll()}.
   * However, the implementation of this method must ensure the content of
   * {@code dest} appears as the entire {@code source} atomically. So once
   * {@code dest} is visible for readers, the entire content of previous {@code source}
   * is visible.
   *
   * This method is used by IndexWriter to publish commits.
   */
  override
  def rename(source: String, dest: String): Unit = {
    ensureOpen()
    val source1 = ElKeyExt.from_str(source)
    val dest1 = ElKeyExt.from_str(dest)
    sync(Arrays.asList(source, dest))
    val res = for {
      _ <- flh.copy(fromPath=PathKey(dir, source1), toPath=PathKey(dir, dest1))
      _ <- enh.prepend(EnKey(dir, dest1), Bytes.empty)
      _ <- flh.delete(PathKey(dir, source1))
      _ <- enh.remove(EnKey(dir, source1))
      _ <- enh.cleanup(dir)
    } yield ()
    res.fold(
      l => throw new IOException(l.toString),
      _ => ()
    )
  }

  /**
   * Opens a stream for reading an existing file.
   *
   * This method must throw {//link NoSuchFileException}
   * if {@code name} points to a non-existing file.
   *
   * @param name the name of an existing file.
   * //throws IOException in case of I/O error
   */
  override
  def openInput(name: String, context: IOContext): IndexInput = {
    sync(Collections.singletonList(name))
    val name1 = ElKeyExt.from_str(name)
    val res = for {
      bs <- flh.stream(PathKey(dir, name1))
      bs1 <- bs.sequence
    } yield new BytesIndexInput(s"${dir}/${name}", bs1)
    res.fold(
      l => l match {
        case _root_.kvs.FileNotExists(path) => throw new NoSuchFileException(s"${path.dir}/${path.name}")
        case _ => throw new IOException(l.toString)
      },
      r => r
    )
  }

  override def close(): Unit = synchronized {
    isOpen = false
  }

  override
  def getPendingDeletions(): java.util.Set[String] = {
    Collections.emptySet[String]
  }
}

class KvsLockFactory(dir: FdKey) extends LockFactory {
  private[this] val locks = TrieMap.empty[Bytes, Unit]

  override def obtainLock(d: Directory, lockName: String): Lock = {
    val key = Bytes.unsafeWrap(dir.bytes.unsafeArray ++ lockName.getBytes("UTF-8"))
    locks.putIfAbsent(key, ()) match {
      case None => return new KvsLock(key)
      case Some(_) => throw new LockObtainFailedException(key.mkString)
    }
  }

  private[this] class KvsLock(key: Bytes) extends Lock {
    @volatile private[this] var closed = false

    override def ensureValid(): Unit = {
      if (closed) {
        throw new AlreadyClosedException(key.mkString)
      }
      if (!locks.contains(key)) {
        throw new AlreadyClosedException(key.mkString)
      }
    }

    override def close(): Unit = {
      locks -= key
      closed = true
    }
  }
}