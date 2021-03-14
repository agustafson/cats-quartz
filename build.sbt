import sbt._

Global / onChangedBuildSource := ReloadOnSourceChanges

scalaVersion in ThisBuild := "2.13.5"

crossScalaVersions in ThisBuild ++= Seq("2.12.13")
releaseCrossBuild := true

bloopExportJarClassifiers in Global := Some(Set("sources"))

githubSuppressPublicationWarning in Global := true
githubOwner in Global := "ITV"
githubRepository in Global := "cats-quartz"
githubTokenSource in Global := TokenSource.Environment("GITHUB_TOKEN") || TokenSource.GitConfig("github.token")

ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches += RefPredicate.StartsWith(Ref.Tag("v"))
ThisBuild / githubWorkflowPublish := Seq(WorkflowStep.Sbt(List("ci-release")))

val commonSettings: Seq[Setting[_]] = Seq(
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.3" cross CrossVersion.full),
  organization := "com.itv",
  bloopAggregateSourceDependencies in Global := true,
)

def createProject(projectName: String): Project =
  Project(projectName, file(projectName))
    .settings(commonSettings)
    .settings(name := s"cats-quartz-$projectName")

lazy val root = (project in file("."))
  .aggregate(core, extruder, docs)
  .settings(commonSettings)
  .settings(
    publish / skip := true,
  )

lazy val core = createProject("core")
  .settings(
    libraryDependencies ++= Seq(
      "org.quartz-scheduler" % "quartz"                          % Versions.quartz exclude ("com.zaxxer", "HikariCP-java7"),
      "org.typelevel"       %% "cats-effect"                     % Versions.catsEffect,
      "co.fs2"              %% "fs2-io"                          % Versions.fs2,
      "org.scalatest"       %% "scalatest"                       % Versions.scalatest           % Test,
      "org.scalatestplus"   %% "scalacheck-1-14"                 % Versions.scalatestScalacheck % Test,
      "com.dimafeng"        %% "testcontainers-scala-scalatest"  % Versions.testContainers      % Test,
      "com.dimafeng"        %% "testcontainers-scala-postgresql" % Versions.testContainers      % Test,
      "org.postgresql"       % "postgresql"                      % Versions.postgresql          % Test,
      "com.zaxxer"           % "HikariCP"                        % Versions.hikari              % Test,
      "org.flywaydb"         % "flyway-core"                     % Versions.flyway              % Test,
      "ch.qos.logback"       % "logback-classic"                 % Versions.logback             % Test,
    ),
  )

lazy val extruder = createProject("extruder")
  .dependsOn(core)
  .settings(
    resolvers += Resolver.bintrayRepo("janstenpickle", "extruder"),
    libraryDependencies ++= Seq(
      "io.extruder"       %% "extruder-core"   % Versions.extruder,
      "org.scalatest"     %% "scalatest"       % Versions.scalatest           % Test,
      "org.scalatestplus" %% "scalacheck-1-14" % Versions.scalatestScalacheck % Test,
      "org.scalamock"     %% "scalamock"       % Versions.scalamock           % Test,
      "ch.qos.logback"     % "logback-classic" % Versions.logback             % Test,
    ),
  )

lazy val docs = project
  .in(file("cats-quartz-docs"))
  .enablePlugins(MdocPlugin)
  .settings(commonSettings)
  .settings(
    skip in publish := true,
    mdocOut := baseDirectory.in(ThisBuild).value,
    mdocVariables := Map(
      "CATS_QUARTZ_VERSION" -> version.value
    ),
    releaseProcess := Seq[ReleaseStep](
      ReleasePlugin.autoImport.releaseStepInputTask(MdocPlugin.autoImport.mdoc),
      ReleaseMdocStateTransformations.commitMdoc,
    ),
  )
  .dependsOn(core, extruder)

addCommandAlias("buildCatsQuartz", ";clean;+test;mdoc")
