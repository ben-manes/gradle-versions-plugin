package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.reporter.Reporter
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentFilter
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.util.GradleVersion
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.Nullable

/**
 * A task that reports which dependencies have later versions.
 */
open class DependencyUpdatesTask : DefaultTask() { // tasks can't be final

  /** Returns the resolution revision level. */
  @Input
  var revision: String = "milestone"
    get() = (System.getProperties()["revision"] ?: field) as String

  /** Returns the resolution revision level. */
  @Input
  var gradleReleaseChannel: String = RELEASE_CANDIDATE.id
    get() = (System.getProperties()["gradleReleaseChannel"] ?: field) as String

  /** Returns the outputDir destination. */
  @Input
  var outputDir: String =
    "${project.layout.buildDirectory.get().asFile.path.replace(project.projectDir.path + "/", "")}/dependencyUpdates"
    get() = (System.getProperties()["outputDir"] ?: field) as String

  /** Returns the filename of the report. */
  @Input
  @Optional
  var reportfileName: String = "report"
    get() = (System.getProperties()["reportfileName"] ?: field) as String

  /**
   * Sets an output formatting for the task result. It can either be a [String] referencing one of
   * the existing output formatters (i.e. "text", "xml", "json" or "html"), a [String] containing a
   * comma-separated list with any combination of the existing output formatters (e.g. "xml,json"),
   * or a [Reporter]/a [Closure] with a custom output formatting implementation.
   *
   * Use the [outputFormatter] function as an alternative to set a custom output formatting using
   * the trailing closure/lambda syntax.
   */
  var outputFormatter: Any?
    @Internal get() = null
    set(value) {
      outputFormatterArgument =
        when (value) {
          is String -> OutputFormatterArgument.BuiltIn(value)
          is Reporter -> OutputFormatterArgument.CustomReporter(value)
          // Kept for retro-compatibility with "outputFormatter = {}" usages.
          is Closure<*> -> OutputFormatterArgument.CustomAction { value.call(it) }
          else -> throw IllegalArgumentException(
            "Unsupported output formatter provided $value. Please use a String, a Reporter/Closure, " +
              "or alternatively provide a function using the `outputFormatter(Action<Result>)` API.",
          )
        }
    }

  /**
   * Keeps a reference to the latest [OutputFormatterArgument] provided either via the [outputFormatter]
   * property or the [outputFormatter] function.
   */
  private var outputFormatterArgument: OutputFormatterArgument = OutputFormatterArgument.DEFAULT

  @Input
  @Optional
  fun getOutputFormatterName(): String? {
    return with(outputFormatterArgument) {
      if (this is OutputFormatterArgument.BuiltIn) {
        formatterNames
      } else {
        null
      }
    }
  }

  // Groovy generates both get/is accessors for boolean properties unless we manually define some.
  // Gradle will reject this behavior starting in 7.0 so we make sure to define accessors ourselves.
  @Input
  var checkForGradleUpdate: Boolean = true

  @Input
  var gradleVersionsApiBaseUrl: String = "https://services.gradle.org/versions/"

  @Input
  var checkConstraints: Boolean = false

  @Internal
  var filterConfigurations: Spec<Configuration>? = Spec<Configuration> { true }

  @Input
  var checkBuildEnvironmentConstraints: Boolean = false

  @Internal
  @Nullable
  var resolutionStrategy: Closure<Any>? = null
    set(value) {
      field = null
      if (value != null) {
        val closure = value
        resolutionStrategyAction = Action { current ->
          closure.resolveStrategy = Closure.DELEGATE_FIRST
          closure.delegate = current
          if (closure.maximumNumberOfParameters == 0) {
            closure.call()
          } else {
            closure.call(current)
          }
        }
        logger.warn(
          "dependencyUpdates.resolutionStrategy: " +
            "Remove the assignment operator, \"=\", when setting this task property",
        )
      }
    }

  @Nullable
  private var resolutionStrategyAction: Action<in ResolutionStrategyWithCurrent>? = null

  // Pre-computed at configuration time to avoid accessing Task.project at execution time.
  private val taskProjectDir: File = project.projectDir
  private val taskProjectPath: String = project.path
  private val storageKey: String = path
  private var isParallelExecution: Boolean = project.gradle.startParameter.isParallelProjectExecutionEnabled

  init {
    description = "Displays the dependency updates for the project."
    group = "Help"
    outputs.upToDateWhen { false }

    callIncompatibleWithConfigurationCache()

    val thisProject = project
    thisProject.gradle.taskGraph.whenReady { taskGraph ->
      if (taskGraph.hasTask(this@DependencyUpdatesTask)) {
        val filter = filterConfigurations ?: Spec<Configuration> { true }
        val projectConfigs = thisProject.allprojects
          .associateBy({ it }, { it.configurations.matching(filter).toSet() })
        val buildscriptConfigs = thisProject.allprojects
          .associateBy({ it }, { it.buildscript.configurations.toSet() })
        executionDataCache[storageKey] = ExecutionData(
          projectConfigs = projectConfigs,
          buildscriptConfigs = buildscriptConfigs,
          outputFormatterArgument = outputFormatterArgument,
          resolutionStrategyAction = resolutionStrategyAction,
        )
        // Clear fields that may hold closures/objects referencing Project or Configuration
        // so CC serialization doesn't walk into them.
        filterConfigurations = null
        outputFormatterArgument = OutputFormatterArgument.DEFAULT
        resolutionStrategyAction = null
      }
    }
  }

  @TaskAction
  fun dependencyUpdates() {
    requireNoParallel()
    val execData = executionDataCache.remove(storageKey)
    val outputFmt = System.getProperties()["outputFormatter"]
      ?.let { OutputFormatterArgument.BuiltIn(it as String) }
      ?: execData?.outputFormatterArgument
      ?: outputFormatterArgument
    val evaluator =
      DependencyUpdates(
        execData?.projectConfigs ?: emptyMap(),
        execData?.buildscriptConfigs ?: emptyMap(),
        taskProjectDir,
        taskProjectPath,
        execData?.resolutionStrategyAction,
        revision,
        outputFmt,
        outputDir,
        reportfileName,
        checkForGradleUpdate,
        gradleVersionsApiBaseUrl,
        gradleReleaseChannel,
        checkConstraints,
        checkBuildEnvironmentConstraints,
      )
    val reporter = evaluator.run()
    reporter.write()
  }

  private fun requireNoParallel() {
    if (GradleVersion.current() > GradleVersion.version("9.0") && isParallelExecution) {
      throw GradleException("Parallel project execution is not supported, run this task with --no-parallel")
    }
  }

  fun rejectVersionIf(filter: ComponentFilter) {
    resolutionStrategy { strategy ->
      strategy.componentSelection { selection ->
        selection.all(
          Action<ComponentSelectionWithCurrent> { current ->
            @Suppress("SENSELESS_COMPARISON")
            val isNotNull = current.currentVersion != null && current.candidate.version != null
            if (isNotNull && filter.reject(current)) {
              current.reject("Rejected by rejectVersionIf ")
            }
          },
        )
      }
    }
  }

  /**
   * Sets the [resolutionStrategy] to the provided strategy.
   *
   * @param resolutionStrategy the resolution strategy
   */
  fun resolutionStrategy(resolutionStrategy: Action<in ResolutionStrategyWithCurrent>? = null) {
    this.resolutionStrategyAction = resolutionStrategy
    this.resolutionStrategy = null
  }

  /**
   * Sets a custom output formatting for the task result.
   *
   * @param action [Action] implementing the desired custom output formatting.
   */
  fun outputFormatter(action: Action<Result>) {
    outputFormatterArgument = OutputFormatterArgument.CustomAction(action)
  }

  private fun callIncompatibleWithConfigurationCache() {
    this::class.members.find { it.name == "notCompatibleWithConfigurationCache" }
      ?.call(this, "The gradle-versions-plugin isn't compatible with the configuration cache")
  }

  // Holds all execution-time data that may reference Project/Configuration objects
  // or user-provided closures that capture Project references.
  private class ExecutionData(
    val projectConfigs: Map<Project, Set<Configuration>>,
    val buildscriptConfigs: Map<Project, Set<Configuration>>,
    val outputFormatterArgument: OutputFormatterArgument,
    val resolutionStrategyAction: Action<in ResolutionStrategyWithCurrent>?,
  )

  companion object {
    // Stored outside the task's field graph so CC serialization doesn't walk into
    // Project/Configuration references or user-provided closures that capture them.
    // Keyed by task path, cleaned up after use.
    private val executionDataCache = ConcurrentHashMap<String, ExecutionData>()
  }
}
