package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.reporter.Reporter
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.reporter.result.SkippedConfiguration
import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel
import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.CURRENT
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
import org.gradle.api.provider.SetProperty
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
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.annotation.Nullable

/**
 * A task that reports which dependencies have later versions.
 */
@DisableCachingByDefault(because = "Reports the versions a repository publishes, which no input tracks")
open class DependencyUpdatesTask : DefaultTask() { // tasks can't be final

  /** The settings the per-project producers read, kept here so that they are configured as one. */
  @get:Internal
  internal val parameters = DependencyUpdatesParameters()

  /**
   * The settings as the producers resolved them, wired by the plugin from the shared settings so
   * that they are read back as resolved rather than as configured on this project alone. All five
   * are taken from one resolution, so a read cannot mix them. The convention covers a task the
   * plugin did not register, where only this project's own settings are known.
   */
  @get:Internal
  internal val inherited: Property<InheritedSettings> =
    project.objects.property(InheritedSettings::class.java).convention(
      project.provider {
        val revision =
          settingOf(
            parameters.revisionFromCommandLine,
            "revision",
            parameters.revision ?: DEFAULT_REVISION,
          )
        InheritedSettings(
          revision = revision,
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
          rejectOutOfBounds =
            settingOf(
              parameters.rejectOutOfBoundsFromCommandLine,
              parameters.rejectOutOfBounds ?: true,
            ),
          rejectPreReleases =
            settingOf(
              parameters.rejectPreReleasesFromCommandLine,
              parameters.rejectPreReleases ?: false,
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

  private var gradleReleaseChannelSetting: String? = null

  /**
   * Returns which Gradle releases are printed: `release-candidate`, unless the build turns
   * [rejectPreReleases] on, and then `current` alone. Stating this property in the build, passing the
   * option, or setting the system property is read ahead of that, so a build that leaves out every
   * dependency's pre-release step and still wants the Gradle release candidate can say so.
   *
   * Derived rather than fixed so that one setting answers for both. The Gradle row is printed with
   * the release candidate after the newest release, the same breadcrumb used for every dependency
   * row, and a report with the second step left out of every dependency row while Gradle keeps it
   * puts two opposite policies in one file.
   */
  @get:Input
  var gradleReleaseChannel: String
    get() =
      settingOf(
        gradleReleaseChannelFromCommandLine,
        "gradleReleaseChannel",
        gradleReleaseChannelSetting
          ?: if (rejectPreReleases) CURRENT.id else RELEASE_CANDIDATE.id,
      )
    set(value) {
      gradleReleaseChannelSetting = value
    }

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
  var rejectOutOfBounds: Boolean
    get() = inherited.get().rejectOutOfBounds
    set(value) {
      parameters.rejectOutOfBounds = value
    }

  /** Leaves out the versions outside a declared bound for this invocation alone. */
  @Option(
    option = "reject-out-of-bounds",
    description = "Leaves out the versions outside a declared bound or a consumed platform's.",
  )
  internal fun setRejectOutOfBoundsFromCommandLine(rejectOutOfBounds: Boolean) {
    parameters.rejectOutOfBoundsFromCommandLine = rejectOutOfBounds
  }

  /**
   * Whether the pre-release step is left out of the report, the newest candidate the pre-release
   * check rejects while the version in use is not itself a pre-release. A convention the built-in
   * markers do not cover is added to the check with [preReleaseVersionIf]. Off by default, under
   * every revision, so the step is printed after the newest release rather than in place of it. It
   * governs [gradleReleaseChannel]'s default as well, so one setting answers for both rows.
   */
  @get:Input
  var rejectPreReleases: Boolean
    get() = inherited.get().rejectPreReleases
    set(value) {
      parameters.rejectPreReleases = value
    }

  /** Leaves out a pre-release candidate, or lets one through, for this invocation alone. */
  @Option(
    option = "reject-pre-releases",
    description = "Leaves out a pre-release candidate when the current version is not a pre-release.",
  )
  internal fun setRejectPreReleasesFromCommandLine(rejectPreReleases: Boolean) {
    parameters.rejectPreReleasesFromCommandLine = rejectPreReleases
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
        // property and would leave it reading back as unset. Applied by delegating a copy of the
        // closure rather than by project.configure, since that reads Task.project, which the
        // configuration cache forbids at execution, and execution is where the report applies this.
        parameters.resolutionStrategy =
          Action<ResolutionStrategyWithCurrent> { current ->
            @Suppress("UNCHECKED_CAST")
            val configure = value.clone() as Closure<Any>
            configure.resolveStrategy = Closure.DELEGATE_FIRST
            configure.delegate = current
            configure.call(current)
          }
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
   * The aggregation coordinates that Gradle substituted onto no project, wired by the plugin from
   * the resolution result of the configuration the partial results are collected from.
   */
  @get:Internal
  val unaggregatedCoordinates: SetProperty<String> =
    project.objects.setProperty(String::class.java)

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

  /** Whether this report's own rules have already been reported as unstorable in the cache. */
  private var rulesWithheldFromCache = false

  init {
    description = "Displays the dependency updates for the project."
    group = "Help"
    outputs.upToDateWhen { false }
    parameters.onSettingStored = { captured -> withholdRulesFromCache(captured) }
  }

  /**
   * Discards the configuration cache entry where a report's own rules reach their own build
   * script, which the cache cannot serialize. Discarding the entry keeps the report correct and the
   * build running, where storing it would fail outright and there is no way to turn the cache off
   * under isolated projects.
   *
   * Only a Kotlin script is withheld for: a Groovy closure reaches its script as well, and is
   * serialized by substituting the owner, so those reports keep their entry.
   * https://github.com/ben-manes/gradle-versions-plugin/issues/1058
   */
  private fun withholdRulesFromCache(captured: Any?) {
    if (rulesWithheldFromCache || !holdsKotlinScript(captured)) {
      return
    }
    rulesWithheldFromCache = true
    notCompatibleWithConfigurationCache(
      "A rule applied to another build's dependency updates reads this build's script.",
    )
    logger.warn(
      "The configuration cache entry for the dependency updates report of $projectPath was " +
        "discarded: a rejectVersionIf, resolutionStrategy or filterDeclaredConfigurations rule " +
        "reads a declaration from the build script, which the cache cannot store where the report " +
        "applies its rules to another build's dependencies. Declare the rule's helpers as a " +
        "compiled class, in buildSrc or an included build, to keep the entry.",
    )
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
    // An older partial is read for the fields written in it, and its latest version is taken as
    // the newest release. Before the pre-release step, the newest accepted candidate was written
    // there instead, which can be a pre-release, so such a row is printed as a release rather than
    // as the step after one. Warned about rather than left silent, since nothing else in the
    // report distinguishes the row.
    val olderPartials = partials.filter { it.formatVersion < PartialResult.FORMAT_VERSION }
    if (olderPartials.isNotEmpty()) {
      logger.warn(
        "A partial result written by an older version of the plugin was read for " +
          "${olderPartials.map { it.projectPath }.sorted().joinToString(", ")}. A pre-release is " +
          "reported there as the version to upgrade to rather than as the step after the newest " +
          "release. Apply one version of the plugin across every project and included build.",
      )
    }
    val missing = aggregatedProjectPaths - partials.map { it.projectPath }.toSet()
    if (missing.isNotEmpty()) {
      logger.warn(
        "The dependency updates report is missing ${missing.sorted().joinToString(", ")}. A project " +
          "must apply the io.github.ben-manes.versions or io.github.ben-manes.versions.contributor " +
          "plugin to be aggregated when isolated projects is enabled, and projects that share a " +
          "group and name are aggregated as one.",
      )
    }
    val unaggregated = unaggregatedCoordinates.get()
    if (unaggregated.isNotEmpty()) {
      logger.warn(
        "Left out of the dependency updates report: ${unaggregated.joinToString(", ")}, " +
          "which no included build was substituted for. Check the coordinates against the group, " +
          "name and version set in the included build. A build included only " +
          "under pluginManagement is not substituted from the including build's dependency graph, " +
          "so declare a plain includeBuild for these coordinates as well. An includeBuild that " +
          "declares a dependencySubstitution block keeps only the rules declared in it, so declare " +
          "a rule for these coordinates there too.",
      )
    }
    val candidatesByProjectPath = partials.associate { it.projectPath to it.candidates }
    // The configuration cache restores the task without the strategy the producers read, so the
    // copy that survives it is read where the live property is gone.
    val strategy: Action<in ResolutionStrategyWithCurrent>? =
      parameters.resolutionStrategy ?: parameters.storedResolutionStrategy
    // The built-in check is applied here only where this report merges a row some other policy
    // resolved, which is the same condition that stores the convention and the exemption for the
    // report. Everywhere else the producers already applied the identical check, under the settings
    // they inherited, so applying it again would add nothing and would read the two predicates from
    // properties that are gone from a restored cache entry. It is applied whether or not
    // `rejectPreReleases` is set, since that setting governs whether the step is printed: a merged
    // row whose producer knew nothing of the convention configured here still has to be moved off
    // the version this report counts as a pre-release.
    val mergesRowsResolvedElsewhere = parameters.mergesRowsResolvedElsewhere
    val reportRules =
      ReportRules(
        strategy,
        logger,
        revision,
        mergesRowsResolvedElsewhere,
        parameters.preReleaseVersionIf ?: parameters.storedPreReleaseVersionIf,
        parameters.exemptFromBuiltInChecksIf ?: parameters.storedExemptFromBuiltInChecksIf,
      )
    // Read from the copy that survives the cache rather than the live property, which is gone by
    // here on a restored entry. The copy is filled only for a report that merges in another build's
    // rows, so a build that aggregates nobody is left with what its producers already filtered.
    val declaredFilter = parameters.storedFilterDeclaredConfigurations
    val projectRows =
      partials
        .flatMap { partial -> partial.statuses.map { it.copy(projectPath = partial.projectPath) } }
        .filter { declaredFilter.keeps(it) }
    val buildscriptRows =
      partials
        .flatMap { partial ->
          partial.buildscriptStatuses.map { it.copy(projectPath = partial.projectPath) }
        }.filter { declaredFilter.keeps(it) }
    val statuses =
      mergeStatuses(reportRules.applyTo(projectRows, candidatesByProjectPath)) +
        mergeStatuses(reportRules.applyTo(buildscriptRows, candidatesByProjectPath))
    val skipped =
      partials
        .flatMap { partial -> partial.skipped.map { SkippedConfiguration(partial.projectPath, it.name, it.reason) } }

    reporterFor(
      statuses, projectPath, logger, revision, outputFormatter(), outputDirectory(), reportfileName,
      checkForGradleUpdate, gradleVersionsApiBaseUrl, gradleReleaseChannel, skipped,
      rejectPreReleases,
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

  /**
   * Adds a convention the built-in markers do not cover, such as graphql-java's `-nf-` builds, to
   * the pre-release check. A version the [filter] matches is a pre-release wherever the check reads
   * one: it is left out under [rejectPreReleases] and its command line option, a build already on
   * one is still shown a newer one, and `isPreRelease` in a [rejectVersionIf] rule is true for it.
   * The convention is part of the built-in check, so it is off wherever that check is, under
   * `rejectPreReleases = false` and by default under the `integration` revision. It is given the
   * version with any build metadata removed, as the markers are, and it is applied to the version
   * in use as well as to the candidate, which for a constraint declared with no dependency beside
   * it is the constraint's own range text. Called more than once on a task, the filters accumulate;
   * a subproject that calls it replaces the root's rather than adding to it, as with the other
   * predicate settings.
   */
  fun preReleaseVersionIf(filter: Spec<String>) {
    val existing = parameters.preReleaseVersionIf
    parameters.preReleaseVersionIf =
      if (existing == null) filter else Spec { existing.isSatisfiedBy(it) || filter.isSatisfiedBy(it) }
  }

  /**
   * Exempts the candidates the [filter] matches from the built-in checks, `rejectPreReleases` and
   * `rejectOutOfBounds`, so that the checks stay on for the rest of the build. A candidate is
   * exempt from both; a filter that reads `!isOutOfDeclaredBounds()` or `!isPreRelease()` keeps that
   * check. The checks are applied with the exemption inside them, so their properties and command
   * line options apply as they do without it. A [rejectVersionIf] rule is applied whatever the
   * filter matches. Called more than once on a task, the filters accumulate; a subproject that
   * calls it replaces the root's rather than adding to it, as with the other predicate settings.
   */
  fun exemptFromBuiltInChecksIf(filter: ComponentFilter) {
    val existing = parameters.exemptFromBuiltInChecksIf
    parameters.exemptFromBuiltInChecksIf =
      if (existing == null) filter else ComponentFilter { existing.reject(it) || filter.reject(it) }
  }

  /** Registers a Groovy [closure] as the exemption, with `candidate` resolved as [rejectVersionIf] does. */
  fun exemptFromBuiltInChecksIf(closure: Closure<*>) {
    exemptFromBuiltInChecksIf(closureFilter(closure))
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
    rejectVersionIf(closureFilter(closure))
  }

  private fun closureFilter(closure: Closure<*>): ComponentFilter =
    ComponentFilter { current ->
      // Selections are evaluated concurrently, so give each its own copy to set the delegate on.
      val invocation = closure.clone() as Closure<*>
      invocation.delegate = current
      DefaultTypeTransformation.castToBoolean(invocation.call(current))
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

/**
 * Whether [status] survives the report's declared-configuration filter, matching the rule the
 * producer applies: an entry with no configuration on it, as an ordinary declaration's is, is kept
 * whatever the filter rejects. A null filter means nothing is configured on this report.
 */
private fun Spec<String>?.keeps(status: PartialStatus): Boolean =
  this == null ||
    status.configurations.isEmpty() ||
    status.configurations.any { isSatisfiedBy(it) }
