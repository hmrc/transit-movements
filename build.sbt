import play.sbt.routes.RoutesKeys
import scoverage.ScoverageKeys
import uk.gov.hmrc.DefaultBuildSettings.integrationTestSettings
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings

val appName = "transit-movements"

lazy val microservice = Project(appName, file("."))
  .enablePlugins(play.sbt.PlayScala, SbtAutoBuildPlugin, SbtGitVersioning, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(
    majorVersion := 0,
    scalaVersion := "2.12.15",
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    PlayKeys.playDefaultPort := 9520,
    // Import models by default in route files
    RoutesKeys.routesImport ++= Seq(
      "uk.gov.hmrc.transitmovements.models._",
      "uk.gov.hmrc.transitmovements.models.Bindings._",
      "java.time.OffsetDateTime"
    )
  )
  .settings(publishingSettings: _*)
  .configs(IntegrationTest)
  .settings(scoverageSettings)
  .settings(scalacSettings)
  .settings(integrationTestSettings(): _*)
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(inThisBuild(buildSettings))

// Scalac options
lazy val scalacSettings = Def.settings(
  // Disable fatal warnings and warnings from discarding values
  scalacOptions ~= {
    opts =>
      opts.filterNot(Set("-Xfatal-warnings", "-Ywarn-value-discard"))
  },
  // Disable dead code warning as it is triggered by Mockito any()
  Test / scalacOptions ~= {
    opts =>
      opts.filterNot(Set("-Ywarn-dead-code"))
  },
  // Disable warnings arising from generated routing code
  scalacOptions += "-Wconf:src=routes/.*:silent"
)

// Scoverage exclusions and minimums
lazy val scoverageSettings = Def.settings(
  Test / parallelExecution := false,
  ScoverageKeys.coverageMinimumStmtTotal := 90,
  ScoverageKeys.coverageFailOnMinimum := true,
  ScoverageKeys.coverageHighlighting := true,
  ScoverageKeys.coverageExcludedPackages := Seq(
    "<empty>",
    "Reverse.*",
    ".*(config|views.*)",
    ".*(BuildInfo|Routes).*"
  ).mkString(";"),
  ScoverageKeys.coverageExcludedFiles := Seq(
    "<empty>",
    "Reverse.*",
    ".*repositories.*",
    ".*documentation.*",
    ".*BuildInfo.*",
    ".*javascript.*",
    ".*Routes.*",
    ".*GuiceInjector",
    ".*Test.*"
  ).mkString(";")
)

lazy val itSettings = Seq(
  // Must fork so that config system properties are set
  fork := true,
  unmanagedResourceDirectories += (baseDirectory.value / "it" / "resources"),
  javaOptions ++= Seq(
    "-Dlogger.resource=it.logback.xml"
  )
)

lazy val buildSettings = Def.settings(
  scalafmtOnCompile := true
)
