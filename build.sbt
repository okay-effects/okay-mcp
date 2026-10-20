/** okay-mcp: the Model Context Protocol as effects, on okay-simple alone; Scala 3.3 LTS and 2.13, the JVM always, JS and Native when asked */
val scala3 = "3.3.8"
val scala2 = "2.13.18"
import sbtcrossproject.{CrossProject, CrossType}
import Platforms._

ThisBuild / organization := "io.github.okay-effects"
ThisBuild / organizationName := "okay-effects"
ThisBuild / homepage := Some(url("https://github.com/okay-effects/okay-mcp"))
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / scalaVersion := scala3
ThisBuild / resolvers += "okay-effects" at "https://okay-effects.github.io/okay-simple/maven"
/** okay-simple's snapshot, with the json and shape modules, published locally until 0.1.1 is released */
val okayEffects = "0.1.1-SNAPSHOT"

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

lazy val root = (project in file(".")).aggregate(core.jvm).settings(name := "okay-mcp-root", publish / skip := true)
