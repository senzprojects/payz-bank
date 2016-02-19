name := "sdbl-trans"

version := "1.0"

scalaVersion := "2.11.7"

libraryDependencies ++= {

  val akkaVersion       = "2.3.9"
  val cassandraVersion  = "2.1.9"

  Seq(
    "com.typesafe.akka"       %% "akka-actor"               % akkaVersion,
    "com.typesafe.akka"       %% "akka-slf4j"               % akkaVersion,
    "com.datastax.cassandra"  % "cassandra-driver-core"     % cassandraVersion,
    "org.slf4j"               % "slf4j-api"                 % "1.7.5",
    "ch.qos.logback"          % "logback-classic"           % "1.0.9"
  )
}

resolvers ++= Seq(
  "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"
)

