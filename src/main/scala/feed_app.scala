package kvs.feed
package app

import akka.actor.{Actor, Props}
import java.io.IOException
import kvs.rng.{ActorSystem, Dba}
import kvs.sharding.*
import proto.*
import zio.*, stream.*, clock.*, console.*

@main
def feedApp: Unit =
  val io: ZIO[Feed & SeqConsistency & Console, Any, Unit] =
    for
      feed <- ZIO.service[kvs.feed.Service]
      seqc <- ZIO.service[SeqConsistency.Service]
      user <- IO.succeed("guest")
      _ <- putStrLn(s"welcome, $user")
      _ <-
        (for
          _ <- putStrLn("add/all/q?")
          s <- getStrLn 
          _ <-
            s match
              case "add" =>
                for
                  bodyRef <- Ref.make("")
                  _ <- putStrLn("enter post")
                  _ <-
                    (for
                      s <- getStrLn
                      _ <-
                        s match
                          case "" => IO.unit
                          case s => bodyRef.update(_ + "\n" + s)
                    yield s).repeatUntilEquals("")
                  body <- bodyRef.get
                  _ <-
                    body.isEmpty match
                      case true => IO.unit
                      case false =>
                        for
                          post <- IO.succeed(Posts.Post(body))
                          answer <- seqc.send(Posts.Add(user, post))
                          _ <- putStrLn(answer.toString)
                        yield ()
                yield ()
              case "all" =>
                Posts.all(user).take(5).tap(x => putStrLn(x._2.body + "\n" + "-" * 10)).runDrain
              case _ => IO.unit
        yield s).repeatUntilEquals("q")
    yield ()

  val name = "app"
  val akkaConf: ULayer[Has[ActorSystem.Conf]] =
    ActorSystem.staticConf(name, kvs.rng.akkaConf(name, "127.0.0.1", 4343) ++ "akka.loglevel=off")
  val actorSystem: TaskLayer[ActorSystem] =
    akkaConf >>> ActorSystem.live
  val dbaConf: ULayer[Has[kvs.rng.Conf]] =
    ZLayer.succeed(kvs.rng.Conf(dir = "target/data"))
  val dba: TaskLayer[Dba] =
    actorSystem ++ dbaConf ++ Clock.live >>> Dba.live
  val feedLayer: TaskLayer[Feed] =
    actorSystem ++ dba >>> kvs.feed.live
  val shardingLayer: TaskLayer[ClusterSharding] =
    actorSystem >>> kvs.sharding.live
  val sqConf: TaskLayer[Has[SeqConsistency.Config]] =
    import Posts.*
    ZLayer.succeed(SeqConsistency.Config(
      "Posts"
    , {
        case Posts.Add(user, post) =>
          for
            _ <- kvs.feed.add(fid(user), post)
          yield "added"
      }
    , {
        case Posts.Add(user, _) => user
      }
    ))
  val seqcLayer: TaskLayer[SeqConsistency] =
    feedLayer ++ shardingLayer ++ sqConf >>> SeqConsistency.live
  
  Runtime.default.unsafeRun(io.provideCustomLayer(feedLayer ++ seqcLayer))

object Posts:
  case class Add(user: String, post: Post)

  case class Post(@N(1) body: String)

  def all(user: String): ZStream[Feed, Err, (Eid, Post)] =
    for
      r <- kvs.feed.all(fid(user))
    yield r
  
  def fid(user: String): String = s"posts.$user"
  
  given Codec[Posts.Post] = caseCodecAuto
end Posts
