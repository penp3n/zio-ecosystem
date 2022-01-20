package org.ziverge

import scalax.collection.Graph
import scalax.collection.GraphEdge.DiEdge

import zhttp.http._
import zhttp.http.Header
import zhttp.service.Server
import zio._
import zio.durationInt
import zio.stream.ZStream
import java.nio.file.Paths
import java.io.File
import java.time.Instant

object CrappySideEffectingCache:
  var fullAppData: Option[FullAppData] = None
  var timestamp: Instant               = Instant.parse("2018-11-30T18:35:24.00Z")

object DependencyServer extends App:

  import upickle.default.{read, write}
  val app =
    Http.collectHttp[Request] {
      case Method.GET -> Root =>
            Http.fromStream {
              ZStream
                .fromFile(Paths.get("src/main/resources/index.html").toFile)
                .refineOrDie(_ => ???)
            }
      case Method.GET -> Root / "compiledJavascript" / "zioecosystemtracker-fastopt.js" =>
            Http.fromStream {
              ZStream
                .fromFile(
                  Paths.get("src/main/resources/compiledJavascript/zioecosystemtracker-fastopt.js").toFile
                )
                .refineOrDie(_ => ???)
            }
      case Method.GET -> Root / "images" / path =>
               Http.fromFile(new File(s"src/main/resources/images/$path")).setHeaders(Headers.contentType("image/svg+xml"))

      case Method.GET -> !! / "projectData" =>
        val appData      = CrappySideEffectingCache.fullAppData.get
        val responseText = Chunk.fromArray(write(appData).getBytes)
          Http.response(
          Response.http(
            status = Status.OK,
            headers =
              Headers
                .contentLength(responseText.length.toLong)
                .combine(Headers.contentType("application/json")),
            data = HttpData.fromStream(ZStream.fromChunk(responseText))
          )
          )
    }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    (
      for
        port <- ZIO(sys.env.get("PORT"))
        _    <- ZIO.debug("PORT result: " + port)
        _ <-
          SharedLogic
            .fetchAppDataAndRefreshCache(ScalaVersion.V2_13)
            .orDie
            .repeat(Schedule.spaced(30.minutes))
            .fork
        _ <- Server.start(port.map(_.toInt).getOrElse(8090), app)
      yield ()
    ).exitCode
end DependencyServer

object SharedLogic:
  def fetchAppDataAndRefreshCache(scalaVersion: ScalaVersion) =
    for
      now <- ZIO(Instant.now())
      ageOfCache = java.time.Duration.between(now, CrappySideEffectingCache.timestamp)
      res <-
        if (
          ageOfCache.compareTo(java.time.Duration.ofHours(1)) > 0 ||
          CrappySideEffectingCache.fullAppData.isEmpty
        )
          println("Getting fresh data")
          fetchAppData(scalaVersion)
        else
          println("Using cached data")
          ZIO(CrappySideEffectingCache.fullAppData.get)
      _ <-
        ZIO {
          CrappySideEffectingCache.fullAppData = Some(res)
          CrappySideEffectingCache.timestamp = now
        }
    yield res

  def fetchAppData(scalaVersion: ScalaVersion): ZIO[Any, Throwable, FullAppData] =
    for
      currentZioVersion: Version <- Maven.projectMetaDataFor(Data.zioCore, scalaVersion).map(_.typedVersion)
      allProjectsMetaData: Seq[ProjectMetaData] <-
        ZIO.foreachPar(Data.projects) { project =>
          Maven.projectMetaDataFor(project, scalaVersion)
        }
      // TODO Do Pull Request query here
      _                             <- ZIO.debug("got first project!")
      graph: Graph[Project, DiEdge] <- ZIO(ScalaGraph(allProjectsMetaData))
      connectedProjects: Seq[ConnectedProjectData] <-
        ZIO.foreachPar(allProjectsMetaData)(x =>
          for
            res <-
              ZIO.fromEither(ConnectedProjectData(x, allProjectsMetaData, graph, currentZioVersion))
            finalProject <- 
              if (res.projectIsUpToDate)
                ZIO.succeed(res)
              else
                res.project.githubOrgAndRepo.map(
                  project =>
                  Github.pullRequests(project).map {
                    prOpt => 
                      res.copy(relevantPr = prOpt)
                  }
                ).getOrElse(ZIO.succeed(res))
                
              // ZIO.debug(res.project.artifactId + " upToDate: " +  res.projectIsUpToDate)
            // TODO Look for PRs here
          yield finalProject
        )
      res =
        FullAppData(
          connectedProjects,
          allProjectsMetaData,
          DotGraph.render(graph),
          currentZioVersion,
          scalaVersion
        )
    yield res
  end fetchAppData
end SharedLogic
