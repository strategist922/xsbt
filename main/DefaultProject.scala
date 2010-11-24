/* sbt -- Simple Build Tool
 * Copyright 2010  Mark Harrah
 */
package sbt

	import std._
	import compile.{Discovered,Discovery}
	import inc.Analysis
	import TaskExtra._
	import Configurations.{Compile => CompileConfig, Test => TestConfig, Runtime => RunConfig, Default => DefaultConfig}
	import ClasspathProject._
	import Types._
	import xsbti.api.Definition

	import org.scalatools.testing.Framework
	import java.io.File

class DefaultProject(val info: ProjectInfo) extends BasicProject
{
	override def name = "test"
}
abstract class BasicProject extends TestProject with MultiClasspathProject with ReflectiveClasspathProject
{
	// easier to demo for now
	override def organization = "org.example"
	override def version = "1.0"

	override def watchPaths: PathFinder = descendents("src","*")

	def javacOptions: Seq[String] = Nil
	def scalacOptions: Seq[String] = Nil

	def outputDirectory = "target": Path
	def cacheDirectory = outputDirectory / "cache"

	def classesDirectory(configuration: Configuration): File =
		configuration match {
			case CompileConfig => outputDirectory / "classes"
			case x => outputDirectory / (x.toString + "-classes")
		}

	lazy val products: Classpath = TaskMap(productsTask)

	 // TODO: it config, include resources, perhaps handle jars v. directories
	def productsTask(conf: Configuration) =
		conf match {
			case CompileConfig | DefaultConfig => makeProducts(compile, compileInputs, name, "")
			case TestConfig => makeProducts(testCompile, testCompileInputs, name, "test-")
			case x => task { Nil }
		}

	lazy val buildScalaInstance: Task[ScalaInstance] = task {
		val provider = info.app.scalaProvider
		ScalaInstance(provider.version, provider)
	}

	lazy val discoverMain: Task[Seq[(Definition,Discovered)]] =
		compile map { analysis => Discovery.applications(Test.allDefs(analysis)) }

	lazy val discoveredMainClasses: Task[Seq[String]] =
		discoverMain map { _ collect { case (definition, discovered) if(discovered.hasMain) => definition.name } }

	lazy val runMainClass: Task[Option[String]] =
		 discoveredMainClasses map { classes => SelectMainClass(Some(SimpleReader readLine _), classes) }

	lazy val pkgMainClass: Task[Option[String]] =
		 discoveredMainClasses map { classes => SelectMainClass(None, classes) }

	lazy val runner: Task[ScalaRun] =
		 buildScalaInstance map { si => new Run(si) }

	lazy val run = (input :^: fullClasspath(RunConfig) :^: runMainClass :^: streams :^: runner :^: KNil) map { case in :+: cp :+: main :+: s :+: r :+: HNil =>	
		val mainClass = main getOrElse error("No main class detected.")
		val classpath = cp.map(x => Path.fromFile(x.data))
		r.run(mainClass, classpath, in.splitArgs, s.log) foreach error
	}

	lazy val testFrameworks: Task[Seq[TestFramework]] = task {
		import TestFrameworks._
		Seq(ScalaCheck, Specs, ScalaTest, ScalaCheckCompat, ScalaTestCompat, SpecsCompat, JUnit)
	}

	lazy val testLoader: Task[ClassLoader] =
		fullClasspath(TestConfig) :^: buildScalaInstance :^: KNil map { case classpath :+: instance :+: HNil =>
			TestFramework.createTestLoader(data(classpath), instance)
		}

	lazy val loadedTestFrameworks: Task[Map[TestFramework,Framework]] =
		testFrameworks :^: streams :^: testLoader :^: KNil map { case frameworks :+: s :+: loader :+: HNil =>
			frameworks.flatMap( f => f.create(loader, s.log).map( x => (f, x)).toIterable ).toMap
		}
		
	lazy val discoverTest: Task[(Seq[TestDefinition], Set[String])] =
		loadedTestFrameworks :^: testCompile :^: KNil map { case frameworkMap :+: analysis :+: HNil =>
			Test.discover(frameworkMap.values.toSeq, analysis)
		}
	lazy val definedTests: Task[Seq[TestDefinition]] = discoverTest.map(_._1)

	lazy val testOptions: Task[Seq[TestOption]] = task { Nil }

	lazy val test = (loadedTestFrameworks :^: testOptions :^: testLoader :^: definedTests :^: streams :^: KNil) flatMap {
		case frameworkMap :+: options :+: loader :+: discovered :+: s :+: HNil =>

		val toTask = (testTask: NamedTestTask) => task { testTask.run() } named(testTask.name)
		def dependsOn(on: Iterable[Task[_]]): Task[Unit] = task { () } dependsOn(on.toSeq : _*)

		val (begin, work, end) = Test(frameworkMap, loader, discovered, options, s.log)
		val beginTasks = dependsOn( begin.map(toTask) ) // test setup tasks
		val workTasks = work.map(w => toTask(w) dependsOn(beginTasks) )  // the actual tests
		val endTasks = dependsOn( end.map(toTask) ) // tasks that perform test cleanup and are run regardless of success of tests
		dependsOn( workTasks ) doFinally { endTasks }
	}

	lazy val clean = task {
		IO.delete(outputDirectory)
	}
	lazy val set = input map { in =>
		val Seq(name, value) = in.splitArgs.take(2)
		println(name + "=" + value)
		java.lang.System.setProperty(name, value)
	}

	def sourceFilter: FileFilter = "*.java" | "*.scala"

	def compileTask(inputs: Task[Compile.Inputs]): Task[Analysis] = inputs map Compile.apply

	def compileInputsTask(configuration: Configuration, base: PathFinder, scalaInstance: Task[ScalaInstance]): Task[Compile.Inputs] =
	{
		val dep = dependencyClasspath(configuration)
		(dep, scalaInstance) map { case (cp :+: si :+: HNil) =>
			val log = ConsoleLogger()
			val compilers = Compile.compilers(si)(info.configuration, log)
			val javaSrc = base / "java"
			val scalaSrc = base / "scala"
			val out = "target" / si.actualVersion
				import Path._
			val sources = descendents((javaSrc +++ scalaSrc), sourceFilter) +++ (if(configuration == CompileConfig) info.projectDirectory * (sourceFilter -- defaultExcludes) else Path.emptyPathFinder)
			val classes = classesDirectory(configuration)
			val classpath = classes +: data(cp)
			val analysis = analysisMap(cp)
			val cache = cacheDirectory / "compile" / configuration.toString
			Compile.inputs(classpath, sources.getFiles.toSeq, classes, scalacOptions, javacOptions, javaSrc.getFiles.toSeq, analysis, cache, 100)(compilers, log)
		}
	}

	lazy val compileInputs: Task[Compile.Inputs] = compileInputsTask(Configurations.Compile,  "src" / "main", buildScalaInstance) named(name + "/compile-inputs")
	lazy val compile: Task[Analysis] = compileTask(compileInputs) named(name + "/compile")

	lazy val testCompileInputs: Task[Compile.Inputs] = compileInputsTask(Configurations.Test,  "src" / "test", buildScalaInstance) named(name + "/test-inputs")
	lazy val testCompile: Task[Analysis] = compileTask(testCompileInputs) named(name + "/test")
}