name := """DataLogger"""

version := "1.0.3"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  ws,
  "com.github.nscala-time" %% "nscala-time" % "2.16.0",
  "org.mongodb.scala" %% "mongo-scala-driver" % "1.1.0"
)

mappings in Universal ++=
(baseDirectory.value / "report_template" * "*" get) map
    (x => x -> ("report_template/" + x.getName))
	
//libraryDependencies += "com.google.guava" % "guava" % "19.0"

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

scalacOptions ++= Seq("-feature")

fork in run := false