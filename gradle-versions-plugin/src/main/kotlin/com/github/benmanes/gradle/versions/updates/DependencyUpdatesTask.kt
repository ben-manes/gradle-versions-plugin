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
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.util.GradleVersion
import java.io.File
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
    "${
      project.layout.buildDirectory
        .get()
        .asFile
        .relativeTo(project.layout.projectDirectory.asFile)
        .path
    }/dependencyUpdates"
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

  // Consumed at configuration time only, so it is kept out of the serialized task state.
  @Internal
  @Transient
  var filterConfigurations: Spec<Configuration> = ALL_CONFIGURATIONS

  @Input
  var checkBuildEnvironmentConstraints: Boolean = false

  @Internal
  @Nullable
  @Transient
  var resolutionStrategy: Closure<Any>? = null

  @Nullable
  @Transient
  private var resolutionStrategyAction: Action<in ResolutionStrategyWithCurrent>? = null

  /** The partial results of each project, wired by the plugin from the aggregation variants. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  val partialResults: ConfigurableFileCollection = project.files()

  /** Captured at configuration time; replaces `project.path` at execution. */
  @Internal
  var projectPath: String = project.path

  /** The project paths expected to contribute partial results, wired by the plugin. */
  @Internal
  var aggregatedProjectPaths: Set<String> = emptySet()

  /** Captured at configuration time; replaces `project.file()` at execution. */
  @get:Internal
  val projectDirectory: DirectoryProperty =
    project.objects.directoryProperty().convention(project.layout.projectDirectory)

  init {
    description = "Displays the dependency updates for the project."
    group = "Help"
    outputs.upToDateWhen { false }

    if (!isAggregationEnabled()) {
      callIncompatibleWithConfigurationCache()
    }
  }

  @TaskAction
  fun dependencyUpdates() {
    if (isAggregationEnabled()) {
      aggregateUpdates()
      return
    }
    requireNoParallel()
    project.evaluationDependsOnChildren()
    if (resolutionStrategy != null) {
      val closure = resolutionStrategy!!
      resolutionStrategy { current -> project.configure(current, closure) }
      logger.warn(
        "dependencyUpdates.resolutionStrategy: " +
          "Remove the assignment operator, \"=\", when setting this task property",
      )
    }
    val evaluator =
      DependencyUpdates(
        project, resolutionStrategyAction, revision,
        outputFormatter(), outputDir, reportfileName, checkForGradleUpdate, gradleVersionsApiBaseUrl,
        gradleReleaseChannel, checkConstraints, checkBuildEnvironmentConstraints,
        filterConfigurations,
      )
    val reporter = evaluator.run()
    reporter.write()
  }

  /** Merges the partial results of every project and writes the report. */
  private fun aggregateUpdates() {
    val partials =
      partialResults.files
        .map { PartialResult.fromJson(it.readText()) }
        .sortedBy { it.projectPath }
    val missing = aggregatedProjectPaths - partials.map { it.projectPath }.toSet()
    if (missing.isNotEmpty()) {
      logger.warn(
        "The dependency updates report is missing ${missing.sorted().joinToString(", ")}. A project " +
          "must apply the com.github.ben-manes.versions plugin to be aggregated when isolated " +
          "projects is enabled, and projects that share a group and name are aggregated as one.",
      )
    }
    val statuses =
      mergeStatuses(partials.flatMap { it.statuses }) +
        mergeStatuses(partials.flatMap { it.buildscriptStatuses })

    DependencyUpdates.reporterFor(
      statuses, projectPath, logger, revision, outputFormatter(), outputDirectory(), reportfileName,
      checkForGradleUpdate, gradleVersionsApiBaseUrl, gradleReleaseChannel,
    ).write()
  }

  /** Returns the report destination, resolved against the project directory as `project.file`. */
  private fun outputDirectory(): File {
    val destination = File(outputDir)
    return if (destination.isAbsolute) {
      destination
    } else {
      File(projectDirectory.get().asFile, outputDir)
    }
  }

  /** Freezes the values the per-project producers need before any subproject is evaluated. */
  internal fun freezeInto(
    parameters: DependencyUpdatesParameters,
    project: Project,
  ) {
    if (resolutionStrategy != null) {
      val closure = resolutionStrategy!!
      resolutionStrategy { current -> project.configure(current, closure) }
      logger.warn(
        "dependencyUpdates.resolutionStrategy: " +
          "Remove the assignment operator, \"=\", when setting this task property",
      )
    }
    parameters.revision = revision
    parameters.filterConfigurations = filterConfigurations
    parameters.resolutionStrategy = resolutionStrategyAction
    parameters.checkConstraints = checkConstraints
    parameters.checkBuildEnvironmentConstraints = checkBuildEnvironmentConstraints
  }

  private fun requireNoParallel() {
    if (GradleVersion.current() > GradleVersion.version("9.0") &&
      project.gradle.startParameter.isParallelProjectExecutionEnabled
    ) {
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

  /** Returns the outputDir format. */
  private fun outputFormatter(): OutputFormatterArgument {
    val outputFormatterProperty = System.getProperties()["outputFormatter"] as? String

    return outputFormatterProperty?.let { OutputFormatterArgument.BuiltIn(it) }
      ?: outputFormatterArgument
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
}
