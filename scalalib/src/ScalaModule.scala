package mill
package scalalib

import coursier.Repository
import mill.define.{Target, Task, TaskModule}
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.modules.Jvm.createJar
import mill.scalalib.api.Util.isDotty
import Lib._
import mill.util.Loose.Agg
import mill.api.DummyInputStream

/**
  * Core configuration required to compile a single Scala compilation target
  */
trait ScalaModule extends JavaModule { outer =>
  trait Tests extends TestModule with ScalaModule{
    override def scalaOrganization = outer.scalaOrganization()
    def scalaVersion = outer.scalaVersion()
    override def repositories = outer.repositories
    override def scalacPluginIvyDeps = outer.scalacPluginIvyDeps
    override def scalacOptions = outer.scalacOptions
    override def javacOptions = outer.javacOptions
    override def zincWorker = outer.zincWorker
    override def moduleDeps: Seq[JavaModule] = Seq(outer)
  }

  /**
    * What Scala organization to use
    * @return
    */
  def scalaOrganization: T[String] = T {
    if (isDotty(scalaVersion()))
      "ch.epfl.lamp"
    else
      "org.scala-lang"
  }

  /**
    * What version of Scala to use
    */
  def scalaVersion: T[String]

  override def mapDependencies = T.task{ d: coursier.Dependency =>
    val artifacts =
      if (isDotty(scalaVersion()))
        Set("dotty-library", "dotty-compiler")
      else
        Set("scala-library", "scala-compiler", "scala-reflect")
    if (!artifacts(d.module.name)) d
    else d.copy(module = d.module.copy(organization = scalaOrganization()), version = scalaVersion())
  }

  override def resolveCoursierDependency: Task[Dep => coursier.Dependency] = T.task{
    Lib.depToDependency(_: Dep, scalaVersion(), platformSuffix())
  }

  override def resolvePublishDependency: Task[Dep => publish.Dependency] = T.task{
    publish.Artifact.fromDep(
      _: Dep,
      scalaVersion(),
      mill.scalalib.api.Util.scalaBinaryVersion(scalaVersion()),
      platformSuffix()
    )
  }

  /**
    * Allows you to make use of Scala compiler plugins from maven central
    */
  def scalacPluginIvyDeps = T{ Agg.empty[Dep] }

  def scalaDocPluginIvyDeps = T{ scalacPluginIvyDeps() }

  /**
    * Command-line options to pass to the Scala compiler
    */
  def scalacOptions = T{ Seq.empty[String] }

  def scalaDocOptions = T{ scalacOptions() }

  private val Milestone213 = raw"""2.13.(\d+)-M(\d+)""".r

  def scalaCompilerBridgeSources = T {
    val (scalaVersion0, scalaBinaryVersion0) = scalaVersion() match {
      case Milestone213(_, _) => ("2.13.0-M2", "2.13.0-M2")
      case _ => (scalaVersion(), mill.scalalib.api.Util.scalaBinaryVersion(scalaVersion()))
    }

    val (bridgeDep, bridgeName, bridgeVersion) =
      if (isDotty(scalaVersion0)) {
        val org = scalaOrganization()
        val name = "dotty-sbt-bridge"
        val version = scalaVersion()
        (ivy"$org:$name:$version", name, version)
      } else {
        val org = "org.scala-sbt"
        val name = "compiler-bridge"
        val version = Versions.zinc
        (ivy"$org::$name:$version", s"${name}_$scalaBinaryVersion0", version)
      }

    resolveDependencies(
      repositories,
      Lib.depToDependency(_, scalaVersion0, platformSuffix()),
      Seq(bridgeDep),
      sources = true
    ).map(deps =>
      mill.scalalib.api.Util.grepJar(deps.map(_.path), bridgeName, bridgeVersion, sources = true)
    )
  }

  /**
    * The local classpath of Scala compiler plugins on-disk; you can add
    * additional jars here if you have some copiler plugin that isn't present
    * on maven central
    */
  def scalacPluginClasspath: T[Agg[PathRef]] = T {
    resolveDeps(scalacPluginIvyDeps)()
  }

  /**
    * The ivy coordinates of Scala's own standard library
    */
  def scalaDocPluginClasspath: T[Agg[PathRef]] = T {
    resolveDeps(scalaDocPluginIvyDeps)()
  }

  def scalaLibraryIvyDeps = T{ scalaRuntimeIvyDeps(scalaOrganization(), scalaVersion()) }

  /**
    * Classpath of the Scala Compiler & any compiler plugins
    */
  def scalaCompilerClasspath: T[Agg[PathRef]] = T{
    resolveDeps(
      T.task{
        scalaCompilerIvyDeps(scalaOrganization(), scalaVersion()) ++
        scalaRuntimeIvyDeps(scalaOrganization(), scalaVersion())
      }
    )()
  }
  override def compileClasspath = T{
    transitiveLocalClasspath() ++
    resources() ++
    unmanagedClasspath() ++
    resolveDeps(T.task{compileIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
  }

  override def upstreamAssemblyClasspath = T{
    transitiveLocalClasspath() ++
    unmanagedClasspath() ++
    resolveDeps(T.task{runIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
  }

  override def compile: T[mill.scalalib.api.CompilationResult] = T.persistent{
    zincWorker.worker().compileMixed(
      upstreamCompileOutput(),
      allSourceFiles().map(_.path),
      compileClasspath().map(_.path),
      javacOptions(),
      scalaVersion(),
      scalacOptions(),
      scalaCompilerBridgeSources(),
      scalaCompilerClasspath().map(_.path),
      scalacPluginClasspath().map(_.path),
    )
  }

  override def docJar = T {
    val outDir = T.ctx().dest

    val javadocDir = outDir / 'javadoc
    os.makeDir.all(javadocDir)

    val files = allSourceFiles().map(_.path.toString)

    val pluginOptions = scalaDocPluginClasspath().map(pluginPathRef => s"-Xplugin:${pluginPathRef.path}")
    val compileCp = compileClasspath().filter(_.path.ext != "pom").map(_.path)
    val options = Seq(
      "-d", javadocDir.toNIO.toString,
      "-classpath", compileCp.mkString(":")
    ) ++
      pluginOptions ++
      scalaDocOptions()

    if (files.isEmpty) Result.Success(createJar(Agg(javadocDir))(outDir))
    else {
      zincWorker.worker().docJar(
        scalaVersion(),
        scalaCompilerBridgeSources(),
        scalaCompilerClasspath().map(_.path),
        scalacPluginClasspath().map(_.path),
        files ++ options
      ) match{
        case true => Result.Success(createJar(Agg(javadocDir))(outDir))
        case false => Result.Failure("docJar generation failed")
      }
    }
  }

  /**
    * Opens up a Scala console with your module and all dependencies present,
    * for you to test and operate your code interactively
    */
  def console() = T.command{
    if (T.ctx().log.inStream == DummyInputStream){
      Result.Failure("repl needs to be run with the -i/--interactive flag")
    }else{
      Jvm.runSubprocess(
        mainClass =
          if (isDotty(scalaVersion()))
            "dotty.tools.repl.Main"
          else
            "scala.tools.nsc.MainGenericRunner",
        classPath = runClasspath().map(_.path) ++ scalaCompilerClasspath().map(_.path),
        mainArgs = Seq("-usejavacp"),
        workingDir = os.pwd
      )
      Result.Success()
    }
  }

  /**
    * Dependencies that are necessary to run the Ammonite Scala REPL
    */
  def ammoniteReplClasspath = T{
    localClasspath() ++
    transitiveLocalClasspath() ++
    unmanagedClasspath() ++
    resolveDeps(T.task{
      runIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps() ++
      Agg(ivy"com.lihaoyi:::ammonite:${Versions.ammonite}")
    })()
  }

  /**
    * Opens up an Ammonite Scala REPL with your module and all dependencies present,
    * for you to test and operate your code interactively
    */
  def repl(replOptions: String*) = T.command{
    if (T.ctx().log.inStream == DummyInputStream){
      Result.Failure("repl needs to be run with the -i/--interactive flag")
    }else{
      Jvm.runSubprocess(
        mainClass = "ammonite.Main",
        classPath = ammoniteReplClasspath().map(_.path),
        mainArgs = replOptions,
        workingDir = os.pwd
      )
      Result.Success()
    }

  }

  /**
    * Whether to publish artifacts with name "mill_2.12.4" instead of "mill_2.12"
    */
  def crossFullScalaVersion: T[Boolean] = false

  /**
    * What Scala version string to use when publishing
    */
  def artifactScalaVersion: T[String] = T {
    if (crossFullScalaVersion()) scalaVersion()
    else mill.scalalib.api.Util.scalaBinaryVersion(scalaVersion())
  }

  /**
    * The suffix appended to the artifact IDs during publishing
    */
  def artifactSuffix: T[String] = s"_${artifactScalaVersion()}"

  override def artifactId: T[String] = artifactName() + artifactSuffix()

}
