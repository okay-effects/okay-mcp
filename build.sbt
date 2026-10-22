/** okay-mcp: the Model Context Protocol as effects, on okay-simple alone; Scala 3.3 LTS and 2.13, the JVM always, JS and Native when asked */
val scala3 = "3.3.8"
val scala2 = "2.13.18"
import sbtcrossproject.{CrossProject, CrossType}
import Platforms._

ThisBuild / organization := "io.github.okay-effects"
ThisBuild / organizationName := "okay-effects"
ThisBuild / homepage := Some(url("https://github.com/okay-effects/okay-mcp"))
ThisBuild / version := "0.1.0"
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / scalaVersion := scala3
ThisBuild / resolvers += "okay-effects" at "https://okay-effects.github.io/okay-simple/maven"
/** RELEASES, ON GITHUB PAGES: `sbt publishPages` writes every published module, for both compilers and every platform
 * asked, as a Maven repository into `target/pages-maven/`; that directory goes to the `gh-pages` branch under
 * `maven/`, served at https://okay-effects.github.io/okay-mcp/maven (RELEASING.md) */
val pagesRepository = settingKey[File]("the Maven repository a release is written to, for GitHub Pages")
ThisBuild / pagesRepository := (ThisBuild / baseDirectory).value / "target" / "pages-maven"
ThisBuild / publishTo := Some(MavenCache("pages", (ThisBuild / pagesRepository).value))
/** no API documentation jars, as okay-simple: the sources jars are published, which IDEs read for the documentation */
ThisBuild / Compile / packageDoc / publishArtifact := false
/** okay-simple, from its Pages repository */
val okayEffects = "0.1.1"

def crossBuilt: Seq[Def.Setting[?]] = Seq(
  crossScalaVersions := Seq(scala3, scala2),
  scalacOptions ++= (if (scalaVersion.value.startsWith("3")) Seq("-deprecation", "-feature") else Seq("-deprecation", "-feature", "-Xlint")),
  libraryDependencies += "org.scalameta" %%% "munit" % "1.2.3" % Test)
val platformsAsked: Set[String] =
  (sys.props.get("platforms") orElse sys.env.get("OKAY_PLATFORMS")).getOrElse("jvm").split(',').map(_.trim.toLowerCase).toSet + "jvm"
def platformsOf(can: Set[String]): Seq[sbtcrossproject.Platform] = Seq[sbtcrossproject.Platform](JVMPlatform) ++
  (if (platformsAsked("js") && can("js")) Seq(JSPlatform) else Nil) ++ (if (platformsAsked("native") && can("native")) Seq(NativePlatform) else Nil)
def crossBoth(id: String, dir: String, can: Set[String] = Set("js", "native")): CrossProject =
  CrossProject(id, file(dir))(platformsOf(can): _*).crossType(CrossType.Pure)
    .settings(crossBuilt).jvmConfigure(_.withId(id)).onJS(_.withId(id + "JS")).onNative(_.withId(id + "Native"))

/** JSON-RPC as data; Link, the transport, as an effect (stdio, a script, a loopback); the server; the client and Tool */
lazy val core = crossBoth("core", "core").settings(name := "okay-mcp",
  libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-effects" % okayEffects,
    "io.github.okay-effects" %%% "okay-effects-json" % okayEffects,
    "io.github.okay-effects" %%% "okay-effects-shape" % okayEffects,
    "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects % Test,
    "io.github.okay-effects" %%% "okay-effects-free" % okayEffects % Test))

lazy val root = (project in file("."))
  .aggregate(Seq(core).flatMap(_.projects.values.map(p => p: ProjectReference)): _*)
  .settings(name := "okay-mcp-root", publish / skip := true, crossScalaVersions := Nil)

/** a release into `target/pages-maven/`, on Scala 3.3 and 2.13 — `sbt -Dplatforms=jvm,js,native publishPages` for
 * all three platforms */
addCommandAlias("publishPages", "; ++3.3.8 publish ; ++2.13.18 publish ; ++3.3.8")
