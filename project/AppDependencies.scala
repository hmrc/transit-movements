import play.core.PlayVersion
import sbt._

object AppDependencies {

  private val catsVersion          = "2.7.0"
  private val hmrcMongoVersion     = "0.73.0"
  private val bootstrapPlayVersion = "7.12.0"

  val compile = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-28"   % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-play-28"          % hmrcMongoVersion,
    "uk.gov.hmrc.objectstore" %% "object-store-client-play-28" % "1.0.0",
    "org.typelevel"           %% "cats-core"                   % catsVersion,
    "org.json"                 % "json"                        % "20210307",
    "io.lemonlabs"            %% "scala-uri"                   % "3.6.0",
    "com.typesafe.akka"       %% "akka-slf4j"                  % PlayVersion.akkaVersion,
    "com.lightbend.akka"      %% "akka-stream-alpakka-xml"     % "3.0.4"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"  % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-28" % hmrcMongoVersion,
    "org.mockito"             % "mockito-core"            % "4.5.1",
    "org.scalatest"          %% "scalatest"               % "3.2.12",
    "org.typelevel"          %% "cats-core"               % catsVersion,
    "com.typesafe.play"      %% "play-test"               % PlayVersion.current,
    "com.typesafe.akka"      %% "akka-testkit"            % PlayVersion.akkaVersion,
    "org.mockito"            %% "mockito-scala-scalatest" % "1.17.5",
    "org.pegdown"             % "pegdown"                 % "1.6.0",
    "org.scalatestplus.play" %% "scalatestplus-play"      % "5.1.0",
    "org.scalatestplus"      %% "mockito-3-2"             % "3.1.2.0",
    "org.scalacheck"         %% "scalacheck"              % "1.16.0",
    "com.github.tomakehurst"  % "wiremock-standalone"     % "2.27.2",
    "org.typelevel"          %% "discipline-scalatest"    % "2.1.5",
    "com.vladsch.flexmark"    % "flexmark-all"            % "0.62.2"
  ).map(_ % "test, it")
}
