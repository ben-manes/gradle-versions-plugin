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
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.provider.Provider
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
  var outputDir: String = "build/dependencyUpdates"
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
  @get:Internal
  internal var outputFormatterArgument: OutputFormatterArgument = OutputFormatterArgument.DEFAULT

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

  // Typed as Any? so that CC serialization never sees Spec<Configuration> in the
  // task class signature. Build scripts set this to a Spec<Configuration> and the
  // cast happens in WhenReadyAction at configuration time.
  @get:Internal
  var filterConfigurations: Any? = null

  @Input
  var checkBuildEnvironmentConstraints: Boolean = false

  @Internal
  @Nullable
  var resolutionStrategy: Closure<Any>? = null
    set(value) {
      field = null
      if (value != null) {
        val closure = value
        resolutionStrategyAction =
          Action { current ->
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
      } else {
        resolutionStrategyAction = null
      }
    }

  @get:Internal
  @Nullable
  internal var resolutionStrategyAction: Action<in ResolutionStrategyWithCurrent>? = null

  // Set by the plugin at configuration time so that the task class never calls getProject().
  // Gradle 9.x instruments task classes and flags any getProject() call, even in constructors.
  @get:Internal
  internal var taskProjectDir: File = File(".")

  @get:Internal
  internal var taskProjectPath: String = ""

  // Typed as Any? so that CC serialization and class loading on Gradle 5.x (where
  // BuildService doesn't exist) never see DependencyUpdatesDataService in the task's
  // field graph. On Gradle 6.1+ this holds a Provider<DependencyUpdatesDataService>.
  @get:Internal
  internal var dataServiceProvider: Any? = null

  init {
    description = "Displays the dependency updates for the project."
    group = "Help"
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun dependencyUpdates() {
    val execData = removeExecutionData(path)
    if (execData == null) {
      logger.warn(
        "dependencyUpdates: No pre-resolved data found for task '$path'. " +
          "The report will be empty. This can happen when the configuration cache is " +
          "reused and the whenReady callback did not re-run.",
      )
    }
    val outputFmt =
      System.getProperties()["outputFormatter"]
        ?.let { OutputFormatterArgument.BuiltIn(it as String) }
        ?: execData?.outputFormatterArgument
        ?: outputFormatterArgument

    // Build the reporter from pre-resolved statuses. Resolution happened at configuration
    // time (in WhenReadyAction) because project services are not available at execution time
    // under Gradle 9.x with configuration cache.
    val evaluator =
      DependencyUpdates(
        emptyList(),
        emptyList(),
        taskProjectDir,
        taskProjectPath,
        null,
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
    val reporter =
      evaluator.createReporterFromStatuses(
        execData?.projectStatuses ?: emptySet(),
        execData?.buildscriptStatuses ?: emptySet(),
      )
    reporter.write()
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
    this.resolutionStrategy = null // Clear Closure field first (setter may clear resolutionStrategyAction)
    this.resolutionStrategyAction = resolutionStrategy
  }

  /**
   * Sets a custom output formatting for the task result.
   *
   * @param action [Action] implementing the desired custom output formatting.
   */
  fun outputFormatter(action: Action<Result>) {
    outputFormatterArgument = OutputFormatterArgument.CustomAction(action)
  }

  // Holds pre-resolved dependency statuses and the output formatter.
  // Resolution happens at configuration time (in WhenReadyAction) because Gradle 9.x
  // with configuration cache closes project services after the configuration phase,
  // making it impossible to create detached configurations at execution time.
  internal class ExecutionData(
    val projectStatuses: Set<DependencyStatus>,
    val buildscriptStatuses: Set<DependencyStatus>,
    val outputFormatterArgument: OutputFormatterArgument,
  )

  /**
   * Clears fields that may hold closures/objects referencing Project or Configuration.
   * Note: [outputFormatterArgument] is intentionally NOT cleared here — it is already
   * captured in [ExecutionData] before this method is called, and its types (String,
   * Reporter, Action<Result>) do not reference Project/Configuration.
   */
  internal fun clearConfigurationTimeState() {
    filterConfigurations = null
    resolutionStrategyAction = null
  }

  /**
   * Retrieves and removes execution data for the given task path. Tries the build service
   * first (Gradle 6.1+), then falls back to the static companion-object map (Gradle 5.x).
   */
  @Suppress("UNCHECKED_CAST")
  private fun removeExecutionData(taskPath: String): ExecutionData? {
    val provider = dataServiceProvider
    if (provider != null) {
      try {
        val service = (provider as Provider<DependencyUpdatesDataService>).get()
        val data = service.executionDataMap.remove(taskPath)
        if (data != null) {
          // Also clean the static map if data was dual-written
          executionDataCache.remove(taskPath)
          return data
        }
      } catch (_: Exception) {
        // Fall through to static map
      }
    }
    return executionDataCache.remove(taskPath)
  }

  companion object {
    // Fallback for Gradle < 6.1 where build services are not available.
    // On Gradle 6.1+ the build service is the primary storage; this map is
    // only used when the service is not wired (e.g. old Gradle versions).
    internal val executionDataCache = ConcurrentHashMap<String, ExecutionData>()
  }
}
