package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.reporter.Reporter
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.reporter.result.SkippedConfiguration
import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel
import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentFilter
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.lang.Closure
import org.codehaus.groovy.runtime.typehandling.DefaultTypeTransformation
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.api.tasks.options.OptionValues
import java.io.File
import javax.annotation.Nullable

/**
 * A task that reports which dependencies have later versions.
 */
open class DependencyUpdatesTask : DefaultTask() { // tasks can't be final

  /** The settings the per-project producers read, kept here so that they are configured as one. */
  @get:Internal
  internal val parameters = DependencyUpdatesParameters()

  /**
   * The settings as the producers resolved them, wired by the plugin from the shared settings so
   * that they are read back as resolved rather than as configured on this project alone. All four
   * are taken from one resolution, so a read cannot mix them. The convention covers a task the
   * plugin did not register, where only this project's own settings are known.
   */
  @get:Internal
  internal val inherited: Property<InheritedSettings> =
    project.objects.property(InheritedSettings::class.java).convention(
      project.provider {
        InheritedSettings(
          revision =
            settingOf(
              parameters.revisionFromCommandLine,
              "revision",
              parameters.revision ?: DEFAULT_REVISION,
            ),
          checkConstraints =
            settingOf(
              parameters.checkConstraintsFromCommandLine,
              parameters.checkConstraints ?: false,
            ),
          checkBuildEnvironmentConstraints =
            settingOf(
              parameters.checkBuildEnvironmentConstraintsFromCommandLine,
              parameters.checkBuildEnvironmentConstraints ?: false,
            ),
          rejectOutOfBoundVersions =
            settingOf(
              parameters.rejectOutOfBoundVersionsFromCommandLine,
              parameters.rejectOutOfBoundVersions ?: true,
            ),
        )
      },
    )

  /** Returns the resolution revision level. */
  @get:Input
  var revision: String
    get() = inherited.get().revision
    set(value) {
      parameters.revision = value
    }

  /** Sets the resolution revision level for this invocation alone. */
  @Option(option = "revision", description = "Resolves against this revision level.")
  internal fun setRevisionFromCommandLine(revision: String) {
    parameters.revisionFromCommandLine = revision
  }

  /** Returns the revision levels `gradle help --task dependencyUpdates` lists for the option. */
  @OptionValues("revision")
  fun getRevisionValues(): List<String> = listOf("release", "milestone", "integration")

  private var gradleReleaseChannelFromCommandLine: String? = null

  /** Returns the resolution revision level. */
  @Input
  var gradleReleaseChannel: String = RELEASE_CANDIDATE.id
    get() = settingOf(gradleReleaseChannelFromCommandLine, "gradleReleaseChannel", field)

  /** Sets the Gradle release channel for this invocation alone. */
  @Option(
    option = "gradle-release-channel",
    description = "Reports the Gradle releases of this channel.",
  )
  internal fun setGradleReleaseChannelFromCommandLine(gradleReleaseChannel: String) {
    gradleReleaseChannelFromCommandLine = gradleReleaseChannel
  }

  /** Returns the release channels `gradle help --task dependencyUpdates` lists for the option. */
  @OptionValues("gradle-release-channel")
  fun getGradleReleaseChannelValues(): List<String> = GradleReleaseChannel.values().map { it.id }

  private var outputDirFromCommandLine: String? = null

  /** Returns the outputDir destination. */
  @Input
  var outputDir: String =
    run {
      // Kept absolute when the build directory cannot be made project relative, which is thrown for
      // a build directory redirected to another windows drive, or would otherwise read as the root.
      val buildDirectory = project.layout.buildDirectory.get().asFile
      val relative = buildDirectory.relativeToOrNull(project.layout.projectDirectory.asFile)
      val base = if (relative == null || relative.path.isEmpty()) buildDirectory else relative
      "${base.path}/dependencyUpdates"
    }
    get() = settingOf(outputDirFromCommandLine, "outputDir", field)

  /** Sets where the report is written for this invocation alone. */
  @Option(option = "output-dir", description = "Writes the report into this directory.")
  internal fun setOutputDirFromCommandLine(outputDir: String) {
    outputDirFromCommandLine = outputDir
  }

  private var reportfileNameFromCommandLine: String? = null

  /** Returns the filename of the report. */
  @Input
  @Optional
  var reportfileName: String = "report"
    get() = settingOf(reportfileNameFromCommandLine, "reportfileName", field)

  /** Sets the report's file name for this invocation alone. */
  @Option(option = "report-file-name", description = "Writes the report under this file name.")
  internal fun setReportfileNameFromCommandLine(reportfileName: String) {
    reportfileNameFromCommandLine = reportfileName
  }

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

  private var outputFormatterFromCommandLine: String? = null

  @Input
  @Optional
  fun getOutputFormatterName(): String? {
    namedOutputFormatter()?.let { return it }
    return with(outputFormatterArgument) {
      if (this is OutputFormatterArgument.BuiltIn) {
        formatterNames
      } else {
        null
      }
    }
  }

  /** Returns the formatter named on the command line or in a system property, if either is set. */
  private fun namedOutputFormatter(): String? = outputFormatterFromCommandLine ?: System.getProperty("outputFormatter")

  private var checkForGradleUpdateFromCommandLine: Boolean? = null

  // Groovy generates both get/is accessors for boolean properties unless we manually define some.
  // Gradle will reject this behavior starting in 7.0 so we make sure to define accessors ourselves.
  @Input
  var checkForGradleUpdate: Boolean = true
    get() = settingOf(checkForGradleUpdateFromCommandLine, field)

  /** Reports the available Gradle releases for this invocation alone. */
  @Option(
    option = "check-for-gradle-update",
    description = "Reports the Gradle releases available for the build to upgrade to.",
  )
  internal fun setCheckForGradleUpdateFromCommandLine(checkForGradleUpdate: Boolean) {
    checkForGradleUpdateFromCommandLine = checkForGradleUpdate
  }

  private var gradleVersionsApiBaseUrlFromCommandLine: String? = null

  @Input
  var gradleVersionsApiBaseUrl: String = "https://services.gradle.org/versions/"
    get() = settingOf(gradleVersionsApiBaseUrlFromCommandLine, field)

  /** Reads the Gradle releases from another service for this invocation alone. */
  @Option(
    option = "gradle-versions-api-base-url",
    description = "Reads the Gradle releases from this service rather than the public one.",
  )
  internal fun setGradleVersionsApiBaseUrlFromCommandLine(gradleVersionsApiBaseUrl: String) {
    gradleVersionsApiBaseUrlFromCommandLine = gradleVersionsApiBaseUrl
  }

  @get:Input
  var checkConstraints: Boolean
    get() = inherited.get().checkConstraints
    set(value) {
      parameters.checkConstraints = value
    }

  /** Reports the constrained versions for this invocation alone. */
  @Option(
    option = "check-constraints",
    description = "Reports the versions that a constraints block manages.",
  )
  internal fun setCheckConstraintsFromCommandLine(checkConstraints: Boolean) {
    parameters.checkConstraintsFromCommandLine = checkConstraints
  }

  @get:Internal
  var filterConfigurations: Spec<Configuration>
    get() = parameters.filterConfigurations ?: ALL_CONFIGURATIONS
    set(value) {
      parameters.filterConfigurations = value
    }

  @get:Internal
  var filterDeclaredConfigurations: Spec<String>
    get() = parameters.filterDeclaredConfigurations ?: ALL_DECLARED_CONFIGURATIONS
    set(value) {
      parameters.filterDeclaredConfigurations = value
    }

  @get:Input
  var checkBuildEnvironmentConstraints: Boolean
    get() = inherited.get().checkBuildEnvironmentConstraints
    set(value) {
      parameters.checkBuildEnvironmentConstraints = value
    }

  /** Reports the build environment's constrained versions for this invocation alone. */
  @Option(
    option = "check-build-environment-constraints",
    description = "Reports the versions that a constraints block manages for the build environment.",
  )
  internal fun setCheckBuildEnvironmentConstraintsFromCommandLine(checkBuildEnvironmentConstraints: Boolean) {
    parameters.checkBuildEnvironmentConstraintsFromCommandLine = checkBuildEnvironmentConstraints
  }

  @get:Input
  var rejectOutOfBoundVersions: Boolean
    get() = inherited.get().rejectOutOfBoundVersions
    set(value) {
      parameters.rejectOutOfBoundVersions = value
    }

  /** Leaves out the versions outside a declared bound for this invocation alone. */
  @Option(
    option = "reject-out-of-bound-versions",
    description = "Leaves out the versions outside a declared bound or a consumed platform's.",
  )
  internal fun setRejectOutOfBoundVersionsFromCommandLine(rejectOutOfBoundVersions: Boolean) {
    parameters.rejectOutOfBoundVersionsFromCommandLine = rejectOutOfBoundVersions
  }

  @Internal
  @Nullable
  @Transient
  var resolutionStrategy: Closure<Any>? = null
    set(value) {
      field = value
      // The producers read the strategy while configuring, so an assignment must be adapted as it
      // is made rather than when the task executes.
      if (value != null) {
        // Written directly rather than through resolutionStrategy(Action), which clears this
        // property and would leave it reading back as unset.
        parameters.resolutionStrategy =
          Action<ResolutionStrategyWithCurrent> { current -> project.configure(current, value) }
        parameters.resolutionStrategySet = true
        logger.warn(
          "dependencyUpdates.resolutionStrategy: " +
            "Remove the assignment operator, \"=\", when setting this task property",
        )
      }
    }

  /** The partial results of each project, wired by the plugin from the aggregation variants. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  val partialResults: ConfigurableFileCollection = project.files()

  /** Captured at configuration time; replaces `project.buildTreePath` at execution. */
  @Internal
  var projectPath: String = project.buildTreePath

  /** The build tree paths expected to contribute partial results, wired by the plugin. */
  @Internal
  var aggregatedProjectPaths: Set<String> = emptySet()

  /**
   * The directory the partial results are collected under, wired by the plugin only where it can
   * also identify every project that writes one, as the sweep below would otherwise remove a result
   * in use.
   */
  @get:Internal
  val partialsDirectory: DirectoryProperty = project.objects.directoryProperty()

  /** Where each project's partial result was written before they were collected as one. */
  @get:Internal
  val legacyPartials: ConfigurableFileCollection = project.files()

  /**
   * Whether to remove the partial results that an earlier release wrote into each project's own
   * build directory. Opt in, as the task otherwise writes nothing outside the project it reports
   * from, and `clean` reaches these files in every project that applies a plugin of its own.
   */
  @get:Internal
  @set:Option(
    option = "clean-legacy-partials",
    description = "Removes the partial results that earlier releases wrote into each project.",
  )
  var cleanLegacyPartials: Boolean = false

  /** Captured at configuration time; replaces `project.file()` at execution. */
  @get:Internal
  val projectDirectory: DirectoryProperty =
    project.objects.directoryProperty().convention(project.layout.projectDirectory)

  init {
    description = "Displays the dependency updates for the project."
    group = "Help"
    outputs.upToDateWhen { false }
  }

  /** Merges the partial results of every project and writes the report. */
  @TaskAction
  fun dependencyUpdates() {
    // Sweeps the partial of any project no longer in the build, which no remaining task writes.
    // The files are wired by path rather than discovered, so a stale one is never read, only left
    // behind. The directory stays undeclared as an output, as declaring it would overlap the
    // producers' own output files.
    val expected = partialResults.files
    partialsDirectory.asFile.orNull
      ?.listFiles()
      ?.filter { it.isFile && it !in expected }
      ?.forEach { it.delete() }

    // Removes what an earlier release wrote into each project's own build directory, which `clean`
    // cannot reach in a project that applies no plugin of its own, as `clean` comes from the base
    // plugin. A file that a producer still writes is left alone. The directories are removed only
    // while empty, which is all that delete() will do.
    // https://github.com/ben-manes/gradle-versions-plugin/issues/1040
    if (cleanLegacyPartials) {
      for (legacy in legacyPartials.files - expected) {
        if (legacy.delete()) {
          val reports = legacy.parentFile
          val buildDirectory = reports.parentFile
          if (reports.delete()) {
            buildDirectory.delete()
          }
        }
      }
    }

    val partials =
      partialResults.files
        .map { PartialResult.fromJson(it.readText()) }
        .sortedBy { it.projectPath }
    val missing = aggregatedProjectPaths - partials.map { it.projectPath }.toSet()
    if (missing.isNotEmpty()) {
      logger.warn(
        "The dependency updates report is missing ${missing.sorted().joinToString(", ")}. A project " +
          "must apply the io.github.ben-manes.versions or io.github.ben-manes.versions.contributor " +
          "plugin to be aggregated when isolated projects is enabled, and projects that share a " +
          "group and name are aggregated as one.",
      )
    }
    val statuses =
      mergeStatuses(
        partials.flatMap { partial -> partial.statuses.map { it.copy(projectPath = partial.projectPath) } },
      ) +
        mergeStatuses(
          partials.flatMap { partial ->
            partial.buildscriptStatuses.map { it.copy(projectPath = partial.projectPath) }
          },
        )
    val skipped =
      partials
        .flatMap { partial -> partial.skipped.map { SkippedConfiguration(partial.projectPath, it.name, it.reason) } }

    reporterFor(
      statuses, projectPath, logger, revision, outputFormatter(), outputDirectory(), reportfileName,
      checkForGradleUpdate, gradleVersionsApiBaseUrl, gradleReleaseChannel, skipped,
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
   * Registers a Groovy [closure] as the reject filter, resolving `candidate` against the
   * selection whether the closure uses the bare implicit receiver or an explicit parameter.
   */
  fun rejectVersionIf(closure: Closure<*>) {
    rejectVersionIf(
      ComponentFilter { current ->
        // Selections are evaluated concurrently, so give each its own copy to set the delegate on.
        val invocation = closure.clone() as Closure<*>
        invocation.delegate = current
        DefaultTypeTransformation.castToBoolean(invocation.call(current))
      },
    )
  }

  /**
   * Accumulates the provided strategy with any previously registered one, or clears every
   * previously registered strategy when called with no argument.
   *
   * @param resolutionStrategy the resolution strategy
   */
  @JvmOverloads
  fun resolutionStrategy(resolutionStrategy: Action<in ResolutionStrategyWithCurrent>? = null) {
    val existing = parameters.resolutionStrategy
    parameters.resolutionStrategy =
      if (resolutionStrategy == null || existing == null) {
        resolutionStrategy
      } else {
        Action<ResolutionStrategyWithCurrent> { current ->
          existing.execute(current)
          resolutionStrategy.execute(current)
        }
      }
    parameters.resolutionStrategySet = true
    this.resolutionStrategy = null
  }

  /** Returns the outputDir format. */
  private fun outputFormatter(): OutputFormatterArgument {
    return namedOutputFormatter()?.let { OutputFormatterArgument.BuiltIn(it) }
      ?: outputFormatterArgument
  }

  /** Sets the report's format for this invocation alone, as a comma separated list of names. */
  @Option(
    option = "output-formatter",
    description = "Writes the report in these formats, as a comma separated list.",
  )
  internal fun setOutputFormatterFromCommandLine(outputFormatter: String) {
    outputFormatterFromCommandLine = outputFormatter
  }

  /**
   * Sets a custom output formatting for the task result.
   *
   * @param action [Action] implementing the desired custom output formatting.
   */
  fun outputFormatter(action: Action<Result>) {
    outputFormatterArgument = OutputFormatterArgument.CustomAction(action)
  }
}
