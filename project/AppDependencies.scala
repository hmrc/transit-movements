import play.core.PlayVersion
import sbt.Keys.libraryDependencies
import sbt._
import play.sbt.PlayImport

object AppDependencies {
  val bootstrapVersion = "5.16.0"
  val hmrcMongoVersion = "0.55.0"
  val catsVersion      = "2.6.1"

  val compile = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-28"   % bootstrapVersion,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-play-28"          % hmrcMongoVersion,
    "uk.gov.hmrc.objectstore" %% "object-store-client-play-28" % "0.36.0",
    "uk.gov.hmrc"             %% "play-json-union-formatter"   % "1.15.0-play-28",
    "org.typelevel"           %% "cats-core"                   % catsVersion,
    "io.lemonlabs"            %% "scala-uri"                   % "3.5.0",
    "com.typesafe.akka"       %% "akka-slf4j"                  % PlayVersion.akkaVersion,
    "com.lightbend.akka"      %% "akka-stream-alpakka-xml"     % "3.0.3",
    PlayImport.ws
  )

  val test = Seq(
    "org.scalatest"         %% "scalatest"                % "3.2.10",
    "org.scalatestplus"     %% "scalacheck-1-15"          % "3.2.10.0",
    "uk.gov.hmrc"           %% "bootstrap-test-play-28"   % bootstrapVersion,
    "uk.gov.hmrc.mongo"     %% "hmrc-mongo-test-play-28"  % hmrcMongoVersion,
    "com.typesafe.akka"     %% "akka-testkit"             % PlayVersion.akkaVersion,
    "org.mockito"           %% "mockito-scala-scalatest"  % "1.16.46",
    "com.github.tomakehurst" % "wiremock-jre8-standalone" % "2.31.0",
    "com.vladsch.flexmark"   % "flexmark-all"             % "0.62.2"
  ).map(_ % s"$Test,$IntegrationTest")
}
