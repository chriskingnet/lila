import com.typesafe.sbt.SbtScalariform.autoImport.scalariformPreferences
import play.sbt.Play.autoImport._
import sbt._, Keys._
import scalariform.formatter.preferences._
import scalafix.sbt.ScalafixPlugin
import ScalafixPlugin.scalafixScalacOptions
import ScalafixPlugin.autoImport._

object BuildSettings {

  import Dependencies._

  val globalScalaVersion = "2.11.11"

  def buildSettings = Defaults.coreDefaultSettings ++ Seq(
    organization := "org.lichess",
    scalaVersion := globalScalaVersion,
    resolvers ++= Dependencies.Resolvers.commons,
    scalacOptions ++= compilerOptions,
    scalacOptions ++= scalafixScalacOptions.value,
    incOptions := incOptions.value.withNameHashing(true),
    updateOptions := updateOptions.value.withCachedResolution(true),
    sources in doc in Compile := List(),
    // disable publishing the main API jar
    publishArtifact in (Compile, packageDoc) := false,
    // disable publishing the main sources jar
    publishArtifact in (Compile, packageSrc) := false,
    scalariformPreferences := scalariformPrefs(scalariformPreferences.value),
    // dark magic to keep scalafix away from test code
    scalafix := ScalafixPlugin.scalafixTaskImpl(Seq(Compile)).evaluated
  )

  def scalariformPrefs(prefs: IFormattingPreferences) = prefs
    .setPreference(DanglingCloseParenthesis, Force)
    .setPreference(DoubleIndentConstructorArguments, true)

  def defaultDeps = Seq(scalaz, chess, scalalib, jodaTime, ws, java8compat, specs2)

  def compile(deps: ModuleID*): Seq[ModuleID] = deps map (_ % "compile")
  def provided(deps: ModuleID*): Seq[ModuleID] = deps map (_ % "provided")

  def module(name: String, deps: Seq[sbt.ClasspathDep[sbt.ProjectReference]] = Seq.empty) =
    Project(
      name,
      file("modules/" + name)
    )
      .dependsOn(deps: _*)
      .settings(
        version := "2.0",
        libraryDependencies ++= defaultDeps,
        buildSettings,
        srcMain
      )

  val compilerOptions = Seq(
    "-deprecation", "-unchecked", "-feature", "-language:_",
    "-Xfatal-warnings",
    "-Ywarn-dead-code",
    // "-Ywarn-unused-import",
    // "-Ywarn-unused",
    // "-Xlint:missing-interpolator",
    // "-Ywarn-unused-import",
    "-Ybackend:GenBCode", "-Ydelambdafy:method", "-target:jvm-1.8"
  )

  val srcMain = Seq(
    scalaSource in Compile := (sourceDirectory in Compile).value,
    scalaSource in Test := (sourceDirectory in Test).value
  )

  def projectToRef(p: Project): ProjectReference = LocalProject(p.id)
}
