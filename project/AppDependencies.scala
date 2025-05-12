import sbt.*

object AppDependencies {

  private val catsVersion          = "2.13.0"
  private val hmrcMongoVersion     = "2.6.0"
  private val hmrcBootstrapVersion = "9.11.0"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-30"    % hmrcBootstrapVersion,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-play-30"           % hmrcMongoVersion,
    "uk.gov.hmrc.objectstore" %% "object-store-client-play-30"  % "2.1.0",
    "org.typelevel"           %% "cats-core"                    % catsVersion,
    "org.json"                 % "json"                         % "20250107",
    "io.lemonlabs"            %% "scala-uri"                    % "4.0.3",
    "org.apache.pekko"        %% "pekko-slf4j"                  % "1.1.3",
    "org.apache.pekko"        %% "pekko-connectors-xml"         % "1.1.0",
    "uk.gov.hmrc"             %% "internal-auth-client-play-30" % "3.1.0",
    "uk.gov.hmrc"             %% "crypto-json-play-30"          % "8.2.0",
    "org.apache.pekko"        %% "pekko-protobuf-v3"            % "1.1.3",
    "org.apache.pekko"        %% "pekko-serialization-jackson"  % "1.1.3",
    "org.apache.pekko"        %% "pekko-stream"                 % "1.1.3",
    "org.apache.pekko"        %% "pekko-actor-typed"            % "1.1.3"
  )

  val test: Seq[ModuleID] = Seq(
    "org.apache.pekko"  %% "pekko-testkit"           % "1.1.3",
    "org.scalatest"     %% "scalatest"               % "3.2.19",
    "uk.gov.hmrc"       %% "bootstrap-test-play-30"  % hmrcBootstrapVersion,
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-30" % hmrcMongoVersion,
    "org.typelevel"     %% "cats-core"               % catsVersion,
    "org.pegdown"        % "pegdown"                 % "1.6.0",
    "org.scalacheck"    %% "scalacheck"              % "1.18.1",
    "org.mockito"        % "mockito-core"            % "5.17.0",
    "org.scalatestplus" %% "mockito-5-12"            % "3.2.19.0",
    "org.scalacheck"    %% "scalacheck"              % "1.18.1",
    "org.scalatestplus" %% "scalacheck-1-18"         % "3.2.19.0"
  ).map(_ % Test)
}
