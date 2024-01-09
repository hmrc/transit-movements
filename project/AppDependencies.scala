import sbt._

object AppDependencies {

  private val catsVersion          = "2.9.0"
  private val hmrcMongoVersion     = "1.4.0"
  private val hmrcBootstrapVersion = "8.4.0"

  val compile = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-30"    % hmrcBootstrapVersion,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-play-30"           % hmrcMongoVersion,
    "uk.gov.hmrc.objectstore" %% "object-store-client-play-30"  % "1.3.0",
    "org.typelevel"           %% "cats-core"                    % catsVersion,
    "org.json"                 % "json"                         % "20230227",
    "io.lemonlabs"            %% "scala-uri"                    % "3.6.0",
    "org.apache.pekko"        %% "pekko-slf4j"                  % "1.0.1",
    "org.apache.pekko"        %% "pekko-connectors-xml"         % "1.0.1",
    "uk.gov.hmrc"             %% "internal-auth-client-play-30" % "1.8.0",
    "uk.gov.hmrc"             %% "crypto-json-play-30"          % "7.6.0"
  )

  val test = Seq(
    "uk.gov.hmrc"       %% "bootstrap-test-play-30"  % hmrcBootstrapVersion,
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-30" % hmrcMongoVersion,
    "org.typelevel"     %% "cats-core"               % catsVersion,
    "org.apache.pekko"  %% "pekko-testkit"           % "1.0.2",
    "org.pegdown"        % "pegdown"                 % "1.6.0",
    "org.mockito"       %% "mockito-scala-scalatest" % "1.17.14",
    "org.scalacheck"    %% "scalacheck"              % "1.16.0",
    "org.scalatestplus" %% "mockito-3-2"             % "3.1.2.0",
    "org.scalacheck"    %% "scalacheck"              % "1.16.0",
    "org.typelevel"     %% "discipline-scalatest"    % "2.1.5"
  ).map(_ % Test)
}
