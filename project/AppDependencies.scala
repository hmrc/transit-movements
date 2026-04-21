import sbt.*

object AppDependencies {

  private val catsVersion          = "2.13.0"
  private val hmrcMongoVersion     = "2.12.0"
  private val hmrcBootstrapVersion = "10.7.0"
  private val pekkoVersion = "1.5.0"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-30"    % hmrcBootstrapVersion,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-play-30"           % hmrcMongoVersion,
    "uk.gov.hmrc.objectstore" %% "object-store-client-play-30"  % "2.5.0",
    "org.typelevel"           %% "cats-core"                    % catsVersion,
    "org.json"                 % "json"                         % "20251224",
    "io.lemonlabs"            %% "scala-uri"                    % "4.0.3",
    "org.apache.pekko"        %% "pekko-slf4j"                  % pekkoVersion,
    "org.apache.pekko"        %% "pekko-connectors-xml"         % "1.3.0",
    "uk.gov.hmrc"             %% "internal-auth-client-play-30" % "4.3.0",
    "uk.gov.hmrc"             %% "crypto-json-play-30"          % "8.4.0",
    "org.apache.pekko"        %% "pekko-protobuf-v3"            % pekkoVersion,
    "org.apache.pekko"        %% "pekko-serialization-jackson"  % pekkoVersion,
    "org.apache.pekko"        %% "pekko-stream"                 % pekkoVersion,
    "org.apache.pekko"        %% "pekko-actor-typed"            % pekkoVersion
  )

  val test: Seq[ModuleID] = Seq(
    "org.apache.pekko"  %% "pekko-testkit"           % pekkoVersion,
    "org.scalatest"     %% "scalatest"               % "3.2.20",
    "uk.gov.hmrc"       %% "bootstrap-test-play-30"  % hmrcBootstrapVersion,
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-30" % hmrcMongoVersion,
    "org.typelevel"     %% "cats-core"               % catsVersion,
    "org.scalacheck"    %% "scalacheck"              % "1.19.0",
    "org.mockito"        % "mockito-core"            % "5.23.0",
    "org.scalatestplus" %% "mockito-5-12"            % "3.2.19.0",
    "org.scalatestplus" %% "scalacheck-1-18"         % "3.2.19.0"
  ).map(_ % Test)
}
