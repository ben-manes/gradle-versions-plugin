package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.claims
import com.github.benmanes.gradle.versions.reporter.projectsLabel
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentFilter
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.VerificationType
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.internal.StartParameterInternal
import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.TaskProvider
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

// Not prefixed with "dependencyUpdates": a project with only a producer would otherwise let
// Gradle's task abbreviation match "dependencyUpdates" to it and silently succeed with no report.
internal const val PARTIAL_TASK_NAME = "partialDependencyUpdates"
private const val ELEMENTS_CONFIGURATION = "dependencyUpdatesElements"
private const val AGGREGATION_CONFIGURATION = "dependencyUpdatesAggregation"
private const val PUBLISHED_AGGREGATION_CONFIGURATION = "dependencyUpdatesPublishedAggregation"
private const val PARAMETERS_SERVICE = "dependencyUpdatesParameters"
private const val VERIFICATION_TYPE = "dependency-updates"

/** The number of causes joined into a skipped configuration's reason, matching DependencyStatus. */
private const val MAX_FAILURE_CAUSES = 20

/** The type of a compiled Kotlin build script, which the configuration cache cannot store. */
private const val KOTLIN_SCRIPT_TYPE = "org.gradle.kotlin.dsl.KotlinScript"

/** How many captured objects are read before the search below stops. */
private const val MAX_INSPECTED_CAPTURES = 500

/** The filter applied when a task leaves the configurations unrestricted. */
internal val ALL_CONFIGURATIONS = Spec<Configuration> { true }

/** The filter applied when a task leaves the declared configurations unrestricted. */
internal val ALL_DECLARED_CONFIGURATIONS = Spec<String> { true }

/**
 * Returns whether a Kotlin build script is reachable from [value], which the configuration cache
 * cannot serialize. A Kotlin script's top level functions and properties are members of the script
 * class, so a lambda that reads one captures the script itself.
 *
 * A Groovy closure reaches its script too, through its owner, and is deliberately not reported:
 * Gradle serializes that one by substituting the owner, so reporting it would discard the cache
 * entry for the builds that keep it today.
 */
internal fun holdsKotlinScript(value: Any?): Boolean {
  val pending = ArrayDeque(listOfNotNull(value))
  val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
  var inspected = 0
  while (pending.isNotEmpty() && inspected++ < MAX_INSPECTED_CAPTURES) {
    val captured = pending.removeFirst()
    if (!seen.add(captured)) {
      continue
    }
    // Looked up in the object's own loader rather than the plugin's, since a build with no Kotlin
    // script never loads the type there.
    val isScript =
      runCatching {
        captured.javaClass.classLoader?.loadClass(KOTLIN_SCRIPT_TYPE)?.isInstance(captured)
      }.getOrNull()
    if (isScript == true) {
      return true
    }
    // A collection is walked through its elements rather than through its fields: an ArrayList's
    // element array declares no fields of its own, so a script held in an element would be missed.
    runCatching {
      when (captured) {
        is Array<*> -> captured.forEach { element -> element?.let(pending::add) }
        is Iterable<*> -> captured.forEach { element -> element?.let(pending::add) }
        is Map<*, *> ->
          captured.forEach { (key, value) ->
            key?.let(pending::add)
            value?.let(pending::add)
          }
      }
    }
    // Only the fields the class declares are read, which is where a lambda's captured values sit.
    // Walking the inherited ones as well would reach a Groovy closure's owner.
    for (field in runCatching { captured.javaClass.declaredFields }.getOrDefault(emptyArray())) {
      if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) {
        continue
      }
      runCatching {
        field.isAccessible = true
        field.get(captured)
      }.getOrNull()?.let { pending.add(it) }
    }
  }
  return false
}

/** Returns whether isolated projects is enabled, which forbids configuring the other projects. */
internal fun isIsolatedProjectsEnabled(project: Project): Boolean =
  runCatching {
    (project.gradle.startParameter as StartParameterInternal).isolatedProjects.get()
  }.getOrDefault(false)

/**
 * Returns the setting in effect. A command line option applies ahead of a system property, and
 * both apply ahead of what is configured in the build.
 */
internal fun settingOf(
  fromCommandLine: String?,
  systemPropertyName: String,
  configured: String,
): String =
  fromCommandLine
    ?: System.getProperty(systemPropertyName)
    ?: configured

/**
 * Returns the setting in effect for one that reads no system property, with a command line option
 * ahead of what is configured in the build.
 */
internal fun <T : Any> settingOf(
  fromCommandLine: T?,
  configured: T,
): T = fromCommandLine ?: configured

/** The revision level resolved against when neither the build nor an override states one. */
internal const val DEFAULT_REVISION = "milestone"

/** The task settings that a project's producer reads while its input is realized; null is unset. */
internal class DependencyUpdatesParameters {
  var revision: String? = null

  /** Set by the task's command line option, which applies ahead of the two settings below. */
  var revisionFromCommandLine: String? = null

  @Transient
  var filterConfigurations: Spec<Configuration>? = null

  @Transient
  var filterDeclaredConfigurations: Spec<String>? = null
    set(value) {
      field = value
      onRulesChanged?.invoke()
      onOwnReportRule?.invoke()
    }

  @Transient
  var resolutionStrategy: Action<in ResolutionStrategyWithCurrent>? = null
    set(value) {
      field = value
      onRulesChanged?.invoke()
    }

  @Transient
  var preReleaseVersionIf: Spec<String>? = null
    set(value) {
      field = value
      onRulesChanged?.invoke()
    }

  @Transient
  var exemptFromBuiltInChecksIf: ComponentFilter? = null
    set(value) {
      field = value
      onRulesChanged?.invoke()
    }

  /**
   * Whether a row merged into this report was resolved under rules other than this task's, which
   * is the only case where re-applying this task's rules can change an answer. Turning it on stores
   * those rules, and they stay stored as any of them is reconfigured, so they may be set in any
   * order and any number of times.
   */
  var mergesRowsResolvedElsewhere: Boolean = false
    set(value) {
      field = value
      if (value) {
        onRulesChanged?.invoke()
      }
    }

  /**
   * The strategy re-applied at the report, stored where the configuration cache serializes it into
   * the task rather than dropping it with the transient property above. It is filled only for a
   * report that merges rows resolved under another project's or build's own rules, so every other
   * report stays exempt from serializing the action.
   */
  var storedResolutionStrategy: Action<in ResolutionStrategyWithCurrent>? = null
    set(value) {
      field = value
      onSettingStored?.invoke(value)
    }

  /**
   * The filter that leaves entries out of the report, stored where the configuration cache
   * serializes it into the task rather than dropping it with the transient property above. Filled
   * on the same terms as the strategy, so a report with no other policy's rows in it stays exempt
   * from serializing a predicate.
   */
  var storedFilterDeclaredConfigurations: Spec<String>? = null
    set(value) {
      field = value
      onSettingStored?.invoke(value)
    }

  /**
   * The convention the report's pre-release check reads, stored where the configuration cache
   * serializes it into the task rather than dropping it with the transient property above. Filled
   * on the same terms as the strategy, so a report with no other policy's rows in it stays exempt
   * from serializing a predicate.
   */
  var storedPreReleaseVersionIf: Spec<String>? = null
    set(value) {
      field = value
      onSettingStored?.invoke(value)
    }

  /** The exemption the report's built-in checks read, stored on the same terms as the convention. */
  var storedExemptFromBuiltInChecksIf: ComponentFilter? = null
    set(value) {
      field = value
      onSettingStored?.invoke(value)
    }

  /**
   * Notified as each of the two stored fields above is assigned, so that the task can report
   * whether the cache can store them however late the rule, the filter and the aggregated
   * coordinate are declared. Transient, since the question is settled while the build is configured
   * and the answer is serialized into the entry rather than this.
   */
  @Transient
  var onSettingStored: ((Any?) -> Unit)? = null

  /**
   * Notified where a rule of this project's own is declared, so that every report above it stores
   * its rules. A rule declared here resolves this project's rows under a policy an ancestor's
   * report does not apply, which is the case re-applying the rules exists for. Transient, for the
   * same reason as the notification above.
   */
  @Transient
  var onOwnReportRule: (() -> Unit)? = null

  /**
   * Notified as a rule is declared here or as this report starts merging another policy's rows, so
   * that the service refills the stored fields above from the chain this project inherits. The
   * rules are copied from that chain rather than from the fields beside them: a rule declared on an
   * ancestor governs what this project's producers resolve, so reading only what is declared here
   * would leave the merged rows under no rule at all. Transient, for the same reason as the
   * notification above.
   */
  @Transient
  var onRulesChanged: (() -> Unit)? = null

  /** Distinguishes a strategy that was explicitly cleared from one that was never set. */
  var resolutionStrategySet: Boolean = false
    set(value) {
      field = value
      if (value) {
        onOwnReportRule?.invoke()
      }
    }
  var checkConstraints: Boolean? = null
  var checkBuildEnvironmentConstraints: Boolean? = null
  var rejectOutOfBounds: Boolean? = null
  var rejectPreReleases: Boolean? = null
  var checkEmbeddedKotlin: Boolean? = null

  /**
   * Set by the task's command line options. Read ahead of every configured value in the chain, so
   * that an option applies to a project where the setting is configured on its own task.
   */
  var checkConstraintsFromCommandLine: Boolean? = null
  var checkBuildEnvironmentConstraintsFromCommandLine: Boolean? = null
  var rejectOutOfBoundsFromCommandLine: Boolean? = null
  var rejectPreReleasesFromCommandLine: Boolean? = null
  var checkEmbeddedKotlinFromCommandLine: Boolean? = null
}

/**
 * Stores the settings of the task of each project that applies the plugin.
 *
 * A producer reads its settings from here while its input is realized, which is the only channel
 * that isolated projects permits between the project that owns the task settings and the projects
 * that resolve with them. The realization is ordered after every project is configured, so the
 * settings are read live rather than copied at any earlier moment.
 */
internal abstract class DependencyUpdatesParametersService :
  BuildService<BuildServiceParameters.None> {
  private val byPath = ConcurrentHashMap<String, DependencyUpdatesParameters>()

  /**
   * The settings script's own classpath, which the settings plugin publishes here rather than
   * capturing in the hook that reaches each project, as isolated projects forbids sharing that
   * state between the projects a hook configures.
   */
  @Volatile
  var settingsConfigurations: ConfigurationContainer? = null

  /**
   * The directory that the partial results are collected under, which the project at the root path
   * publishes here rather than each producer reading its layout, as isolated projects forbids that
   * between projects. Null outside an isolated projects build where that project aggregates.
   */
  @Volatile
  var partialsDirectory: Provider<Directory>? = null

  private val legacy = ConcurrentHashMap.newKeySet<Provider<RegularFile>>()

  /** Publishes where an earlier release wrote a project's partial result. */
  fun registerLegacy(file: Provider<RegularFile>) {
    legacy.add(file)
  }

  /** Returns where an earlier release wrote the partial result of each project of the build. */
  fun legacyPartials(): List<RegularFile> = legacy.map { it.get() }

  /** The projects a report rule is declared on, rather than inherited by. */
  private val ownReportRules = ConcurrentHashMap.newKeySet<String>()

  /** Publishes the settings of the given project's task to the projects that resolve with them. */
  fun register(
    path: String,
    parameters: DependencyUpdatesParameters,
  ) {
    byPath[path] = parameters
    parameters.onOwnReportRule = { noteOwnReportRule(path) }
    parameters.onRulesChanged = { storeReportRules() }
    // Registered as the task is realized, which is before the build script's configuration of it
    // runs, so a rule already declared here came from a plugin or an earlier hook and would
    // otherwise be missed.
    if (parameters.resolutionStrategySet || parameters.filterDeclaredConfigurations != null) {
      noteOwnReportRule(path)
    }
    if (ownReportRules.any { isBelow(it, path) }) {
      parameters.mergesRowsResolvedElsewhere = true
    }
    // A report already merging another policy's rows may inherit from this project, so its captured
    // rules are refilled now that this project's own are registered.
    storeReportRules()
  }

  /**
   * Refills the stored rules of every report that merges another policy's rows, from the chain
   * each of them inherits. Run over all of them rather than the one where a rule changed, since a
   * rule declared anywhere in the tree reaches every report below it, and the projects are registered in
   * whatever order they are configured.
   *
   * Serialized, as this reads a rule of one project and writes the capture of another while the
   * projects that declare them are configured in parallel under isolated projects. A read of the
   * rule field that straddled a sibling's write to it would otherwise land last and leave the
   * stale value in the capture, which no warning would report. Each rule setter runs its own
   * capture after assigning the field, so once the captures cannot overlap the last one to run is
   * always the one that follows the last assignment.
   */
  @Synchronized
  private fun storeReportRules() {
    byPath.forEach { (path, parameters) ->
      if (parameters.mergesRowsResolvedElsewhere) {
        // Taken from the chain unresolved, so that a report inheriting no rule at all keeps a null
        // rather than a default the configuration cache would then have to serialize.
        val chain = chainOf(path)
        parameters.storedResolutionStrategy =
          chain.firstOrNull { it.resolutionStrategySet }?.resolutionStrategy
        parameters.storedFilterDeclaredConfigurations =
          chain.firstNotNullOfOrNull { it.filterDeclaredConfigurations }
        parameters.storedPreReleaseVersionIf = chain.firstNotNullOfOrNull { it.preReleaseVersionIf }
        parameters.storedExemptFromBuiltInChecksIf =
          chain.firstNotNullOfOrNull { it.exemptFromBuiltInChecksIf }
      }
    }
  }

  /**
   * Records that the given project resolves under its own rules, and marks every report above it
   * as one where re-applying the rules can now change a row. Read in both directions, since a rule
   * may be declared on a project before or after an ancestor's task is registered. Each side writes
   * its own entry before reading the other's, so that neither is missed where the two are
   * configured at once under isolated projects.
   */
  private fun noteOwnReportRule(path: String) {
    if (!ownReportRules.add(path)) {
      return
    }
    byPath.forEach { (ancestor, parameters) ->
      if (isBelow(path, ancestor)) {
        parameters.mergesRowsResolvedElsewhere = true
      }
    }
    storeReportRules()
  }

  /** Whether the first path is a project beneath the second. */
  private fun isBelow(
    path: String,
    ancestor: String,
  ): Boolean = path != ancestor && (ancestor == ":" || path.startsWith("$ancestor:"))

  /** Returns the settings of the project and of each of its ancestors, nearest first. */
  private fun chainOf(path: String): List<DependencyUpdatesParameters> =
    generateSequence(path) { if (it == ":") null else it.substringBeforeLast(':').ifEmpty { ":" } }
      .mapNotNull { byPath[it] }
      .toList()

  /** Returns the effective settings, taking each property from the nearest ancestor that set it. */
  fun resolve(path: String): ResolvedParameters {
    val chain = chainOf(path)
    val revision =
      settingOf(
        fromCommandLine = chain.firstNotNullOfOrNull { it.revisionFromCommandLine },
        systemPropertyName = "revision",
        configured = chain.firstNotNullOfOrNull { it.revision } ?: DEFAULT_REVISION,
      )
    return ResolvedParameters(
      revision = revision,
      filterConfigurations =
        chain.firstNotNullOfOrNull { it.filterConfigurations } ?: ALL_CONFIGURATIONS,
      filterDeclaredConfigurations =
        chain.firstNotNullOfOrNull { it.filterDeclaredConfigurations } ?: ALL_DECLARED_CONFIGURATIONS,
      resolutionStrategy = chain.firstOrNull { it.resolutionStrategySet }?.resolutionStrategy,
      preReleaseVersionIf = chain.firstNotNullOfOrNull { it.preReleaseVersionIf },
      exemptFromBuiltInChecksIf = chain.firstNotNullOfOrNull { it.exemptFromBuiltInChecksIf },
      checkConstraints =
        settingOf(
          fromCommandLine = chain.firstNotNullOfOrNull { it.checkConstraintsFromCommandLine },
          configured = chain.firstNotNullOfOrNull { it.checkConstraints } ?: false,
        ),
      checkBuildEnvironmentConstraints =
        settingOf(
          fromCommandLine =
            chain.firstNotNullOfOrNull { it.checkBuildEnvironmentConstraintsFromCommandLine },
          configured = chain.firstNotNullOfOrNull { it.checkBuildEnvironmentConstraints } ?: false,
        ),
      rejectOutOfBounds =
        settingOf(
          fromCommandLine = chain.firstNotNullOfOrNull { it.rejectOutOfBoundsFromCommandLine },
          configured = chain.firstNotNullOfOrNull { it.rejectOutOfBounds } ?: true,
        ),
      // Off by default, whatever the revision, so that a row whose repository lists a newer
      // pre-release prints it as the step after the newest release. `gradleReleaseChannel` prints
      // the Gradle row's release candidate the same way and defaults to the same answer.
      rejectPreReleases =
        settingOf(
          fromCommandLine = chain.firstNotNullOfOrNull { it.rejectPreReleasesFromCommandLine },
          configured = chain.firstNotNullOfOrNull { it.rejectPreReleases } ?: false,
        ),
      checkEmbeddedKotlin =
        settingOf(
          fromCommandLine = chain.firstNotNullOfOrNull { it.checkEmbeddedKotlinFromCommandLine },
          configured = chain.firstNotNullOfOrNull { it.checkEmbeddedKotlin } ?: false,
        ),
    )
  }
}

/**
 * A project's settings that hold a plain value, read back from the accumulator task. Kept apart
 * from [ResolvedParameters], where the predicates are never serialized into a task's inputs.
 */
internal class InheritedSettings(
  val revision: String,
  val checkConstraints: Boolean,
  val checkBuildEnvironmentConstraints: Boolean,
  val rejectOutOfBounds: Boolean,
  val rejectPreReleases: Boolean,
  val checkEmbeddedKotlin: Boolean,
)

/** The settings that apply to a single project's producer. */
internal class ResolvedParameters(
  val revision: String,
  val filterConfigurations: Spec<Configuration>,
  val filterDeclaredConfigurations: Spec<String>,
  val resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?,
  val preReleaseVersionIf: Spec<String>?,
  val exemptFromBuiltInChecksIf: ComponentFilter?,
  val checkConstraints: Boolean,
  val checkBuildEnvironmentConstraints: Boolean,
  val rejectOutOfBounds: Boolean,
  val rejectPreReleases: Boolean,
  val checkEmbeddedKotlin: Boolean,
)

/** Registers the per-project producers and wires their results into the accumulator task. */
internal fun registerAggregation(
  project: Project,
  accumulator: TaskProvider<DependencyUpdatesTask>,
) {
  val service = parametersService(project.gradle)
  // Read here so that the provider below captures this rather than the project, which the
  // configuration cache cannot serialize.
  val path = project.path
  // Realized after every project is configured, as the producers' inputs are, so that the values
  // read back from the task are the ones the producers resolved with rather than only what is
  // configured on this project. All six are taken from one resolution, so a read cannot mix a
  // stale value with a fresh one.
  val inherited =
    project.provider {
      val resolved = service.get().resolve(path)
      InheritedSettings(
        revision = resolved.revision,
        checkConstraints = resolved.checkConstraints,
        checkBuildEnvironmentConstraints = resolved.checkBuildEnvironmentConstraints,
        rejectOutOfBounds = resolved.rejectOutOfBounds,
        rejectPreReleases = resolved.rejectPreReleases,
        checkEmbeddedKotlin = resolved.checkEmbeddedKotlin,
      )
    }
  accumulator.configure { task ->
    service.get().register(path, task.parameters)
    task.inherited.set(inherited)
  }
  // Realizes the task, so that a configuration block on a task that nothing else realizes is still
  // applied before the producers read the settings.
  project.afterEvaluate { accumulator.get() }

  val aggregation =
    project.configurations.dependencyScope(AGGREGATION_CONFIGURATION) { configuration ->
      configuration.description = "Collects the projects to aggregate dependency updates from."
    }
  // https://github.com/ben-manes/gradle-versions-plugin/issues/781
  // https://github.com/ben-manes/gradle-versions-plugin/issues/1004
  // Detached, so that a build which locks all of its configurations does not lock this one, which
  // contains only the project dependencies that the plugin declares, and has no lock state of
  // its own.
  // A container configuration cannot opt out: deactivating the locking at creation is undone by the
  // build's own `configurations.all` hook, and deactivating it afterwards has no moment that is
  // late enough to win yet early enough for every build, as configure on demand and composite
  // builds resolve this configuration before `projectsEvaluated` fires.
  val results =
    project.configurations.detachedConfiguration().apply {
      // A detached configuration is created in the legacy role, and this one resolves the results
      // of the projects it depends on, the root project included, so it must not match itself.
      isCanBeConsumed = false
      attributes { attributes ->
        attributes.attribute(
          Category.CATEGORY_ATTRIBUTE,
          project.objects.named(Category::class.java, Category.VERIFICATION),
        )
        attributes.attribute(
          VerificationType.VERIFICATION_TYPE_ATTRIBUTE,
          project.objects.named(VerificationType::class.java, VERIFICATION_TYPE),
        )
      }
    }
  // Mirrored rather than extended, which a detached configuration forbids, so that a project
  // declared in the build's own dependencies block is still aggregated. The producer's
  // configuration is requested by name on the way through, as it is for the projects below, so that
  // a project declaring no variant of its own is read by name rather than fallen back to its
  // `default` configuration, whose artifacts are the project's own and not a partial result. Every
  // module dependency is included rather than the project ones alone, as an included build is
  // declared by its coordinates and substituted onto its project only once the graph resolves.
  //
  // Under isolated projects the same dependencies are published as this project's, so that a
  // consumer reading its statuses walks into the projects it aggregates and reads the whole report
  // as one entry. Published as the graph edges rather than as the collected files, which a consumer
  // cannot resolve from its own build without the lock, and where a lenient view would read as an
  // empty report. The edges cost what the artifacts below avoid: a project with the same group and
  // name as another in the consumer's graph is merged away by conflict resolution, which the
  // report's completeness warning then lists as missing.
  val isolated = isIsolatedProjectsEnabled(project)
  val published =
    if (isolated) {
      project.configurations.dependencyScope(PUBLISHED_AGGREGATION_CONFIGURATION) { configuration ->
        configuration.description =
          "The projects that this project's dependency update statuses are published with."
      }
    } else {
      null
    }
  published?.let { scope ->
    project.configurations
      .matching { it.name == ELEMENTS_CONFIGURATION }
      .configureEach { configuration -> configuration.extendsFrom(scope.get()) }
  }
  aggregation.get().dependencies.all { dependency ->
    for (mirror in listOfNotNull(results.dependencies, published?.get()?.dependencies)) {
      mirror.add(
        if (dependency is ModuleDependency) {
          dependency.copy().apply { targetConfiguration = ELEMENTS_CONFIGURATION }
        } else {
          dependency
        },
      )
    }
    // The task re-applies its rules from the state the configuration cache restored, which drops
    // the strategy the producers read, so a copy that survives is kept for a report that can merge
    // another build's rows. Marked as each dependency is declared rather than at one moment of this
    // project's evaluation, so that a coordinate added by a later hook is still seen. Only a
    // coordinate reaches another build; a project of this build is marked instead where a rule is
    // declared on it, which the service does as that rule is declared.
    if (dependency is ExternalModuleDependency) {
      accumulator.configure { task -> task.parameters.mergesRowsResolvedElsewhere = true }
    }
  }

  // Reading the paths across projects is permitted under isolated projects, unlike configuring. A
  // project with no build script cannot apply the plugin there, so naming it in the completeness
  // warning would report what the user has no way to act on.
  val aggregatedPaths =
    project.allprojects.filter { it.buildFile.exists() }.map { it.buildTreePath }.toSet()

  val partialsDirectory = project.layout.buildDirectory.dir("dependencyUpdates/partials")
  accumulator.configure { task ->
    task.projectPath = project.buildTreePath
    task.aggregatedProjectPaths = aggregatedPaths
    task.projectDirectory.set(project.layout.projectDirectory)
    task.partialResults.from(
      results.incoming
        .artifactView { view ->
          view.componentFilter { id -> id is ProjectComponentIdentifier }
          view.lenient(true)
        }.files,
    )
    // The artifact view above is lenient and keeps only the project components, so a coordinate
    // that Gradle substitutes onto no project is dropped without a word and the report is printed
    // short. A misspelled coordinate and one that resolves to a module in a repository are both
    // collected here, as neither was substituted and the two differ in the resolution result only
    // by which repositories are declared in the build. Read from the resolution result, which the
    // lenient view does not filter, and mapped through a provider so that the graph is walked at
    // execution and the configuration cache stores the provider rather than the result.
    task.unaggregatedCoordinates.set(
      results.incoming.resolutionResult.rootComponent.map { root ->
        root.dependencies
          .filterNot { it.isConstraint }
          // Restricted to the coordinates, as this project's own sibling edges are mirrored into
          // the same configuration and a project with no build script registers no producer for
          // one to select, which the completeness warning above leaves out for the same reason.
          .filter { it.requested is ModuleComponentSelector }
          .mapNotNullTo(sortedSetOf()) { dependency ->
            when {
              dependency is UnresolvedDependencyResult -> dependency.requested.displayName
              dependency is ResolvedDependencyResult &&
                dependency.selected.id !is ProjectComponentIdentifier ->
                dependency.selected.id.displayName
              else -> null
            }
          }
      },
    )
  }

  // Declared for every project so that computing the aggregate's dependencies configures each of
  // them, which configure on demand skips when the task is invoked by its path rather than by name.
  // The producer's configuration is requested by name rather than matched by its attributes, so
  // that a project which publishes no variant is skipped instead of falling back to its `default`
  // configuration, whose artifacts are the project's own and not a partial result to read the
  // report from.
  //
  // This project is left out, since what it aggregates is published as its own variant and reading
  // that back would reach itself through it. Its result is wired from its producer instead.
  for (aggregated in project.allprojects.filter { it != project }) {
    project.dependencies.add(
      AGGREGATION_CONFIGURATION,
      project.dependencies.project(
        mapOf("path" to aggregated.path, "configuration" to ELEMENTS_CONFIGURATION),
      ),
    )
  }

  if (isolated) {
    // Isolated projects forbids registering a task in another project, so each applies the plugin
    // and the results are collected as artifacts of those dependencies alone. A project that does
    // not apply the plugin has no producer and is omitted, which only a settings plugin could fix;
    // gradle.lifecycle.beforeProject is never invoked for a callback added by a project.
    // https://docs.gradle.org/current/userguide/isolated_projects.html
    //
    // The destination is published for the producers that those projects register, as a settings
    // plugin reaches a project that has no build script and it would otherwise be given a build
    // directory to hold nothing else. Only the project at the root path publishes, as a mid tree
    // aggregate reads the projects it does not own as variant artifacts, for which the file's
    // location does not matter. Compared by path rather than to project.rootProject, which is
    // forbidden here.
    // https://github.com/ben-manes/gradle-versions-plugin/issues/1040
    //
    // The projects publish where an earlier release wrote their results as well, which the cleanup
    // reaches through the same channel. Realized while the work graph is assembled, as a cached
    // entry configures no project to publish them a second time.
    if (project.path == ":") {
      service.get().partialsDirectory = partialsDirectory
      accumulator.configure { task ->
        task.legacyPartials.from(project.provider { service.get().legacyPartials() })
      }
    }
    // Wired from the producer rather than read back as this project's own variant, which the
    // aggregation no longer includes.
    val producer = registerProducer(project, service)
    accumulator.configure { task ->
      task.partialResults.from(producer.task.flatMap { it.outputFile })
    }
  } else {
    // Swept only here, as the artifacts are the only record of the projects under isolated
    // projects and they omit the ones that conflict resolution merges away, whose own report is
    // still written where its producer put it. A result left over from a project no longer in the
    // build is left behind rather than risk removing one in use, and is never read, as the results
    // are wired by path rather than discovered.
    accumulator.configure { task -> task.partialsDirectory.set(partialsDirectory) }
    // Published as this project's own artifacts, so that a consumer that declares the build by its
    // coordinates reads the whole report as one entry. The files are published rather than the
    // projects as dependencies, so that no graph node is added for conflict resolution to merge
    // away, and no configuration of this build has to resolve from the consumer's.
    val publishable = project.files()
    publishAggregatedResults(project, publishable)
    // The results are wired as task outputs too, as module conflict resolution would otherwise drop
    // every project that shares a group and name with a sibling from the artifacts.
    project.allprojects { aggregated ->
      // A project that another copy of the plugin claimed has a producer of that copy's type
      // registered in it, which this one cannot wire to its accumulator. That copy reports the
      // project instead.
      if (claims(aggregated)) {
        // Written under the project that aggregates rather than each project's own build
        // directory, so that no build directory is created in a project that exists only for a
        // nested include. Passed rather than published, as this project registers the
        // producer itself and so needs no channel to reach it.
        // https://github.com/ben-manes/gradle-versions-plugin/issues/1040
        val outputFile = partialsDirectory.map { it.file(partialFileName(aggregated.path)) }
        val producer = registerProducer(aggregated, service, outputFile)
        val legacy = aggregated.layout.buildDirectory.file("dependencyUpdates/partial.json")
        if (aggregated != project) {
          // The destination is published rather than the task's output, so that resolving these
          // files does not create the task. See the comment on Producer.
          publishable.from(producer.destination)
          publishable.builtBy(producer.task)
        }
        accumulator.configure { task ->
          task.partialResults.from(producer.task.flatMap { it.outputFile })
          task.legacyPartials.from(legacy)
        }
      }
    }
  }
}

/** Registers the task and outgoing variant that publish a single project's statuses. */
internal fun registerProducer(project: Project): TaskProvider<DependencyUpdatesPartialTask> =
  registerProducer(project, parametersService(project.gradle)).task

/** Publishes the settings script's classpath to the project that accumulates the report. */
internal fun publishSettingsClasspath(
  gradle: Gradle,
  configurations: ConfigurationContainer,
) {
  parametersService(gradle).get().settingsConfigurations = configurations
}

/** Returns the build's shared parameters service, registering it if this is the first use. */
private fun parametersService(gradle: Gradle): Provider<DependencyUpdatesParametersService> =
  gradle.sharedServices
    .registerIfAbsent(PARAMETERS_SERVICE, DependencyUpdatesParametersService::class.java) { }

/**
 * Returns a distinct file name for the partial result of the project at [path]. The hash keeps
 * paths that flatten alike apart, as `:lib:core` and `:lib-core` may both exist in one build.
 */
private fun partialFileName(path: String): String {
  val name = if (path == ":") "root" else path.removePrefix(":").replace(':', '-')
  return "$name-${Integer.toHexString(path.hashCode())}.json"
}

/**
 * A project's producer task and the file it writes.
 *
 * The destination is held apart from the task so that what the project publishes reads where the
 * result will be written without creating the task. An outgoing artifact backed by
 * `task.flatMap { it.outputFile }` creates the task on the first query, and Gradle queries a
 * configuration's artifacts during the tooling model phase, one thread per project and in parallel
 * under `org.gradle.tooling.parallel`, so two threads race to create the same task and the IDE sync
 * fails. Reading the destination is a pure read whatever thread asks.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/1135
 */
private class Producer(
  val task: TaskProvider<DependencyUpdatesPartialTask>,
  val destination: Provider<RegularFile>,
)

private fun registerProducer(
  project: Project,
  service: Provider<DependencyUpdatesParametersService>,
  outputFile: Provider<RegularFile>? = null,
): Producer {
  val tasks = project.tasks
  if (tasks.names.contains(PARTIAL_TASK_NAME)) {
    // Registered by another copy of the plugin, whose destination only the task itself carries, so
    // it is created here while the project is still being configured rather than left for whichever
    // thread queries the artifacts first.
    val existing = tasks.named(PARTIAL_TASK_NAME, DependencyUpdatesPartialTask::class.java)
    return Producer(existing, existing.get().outputFile)
  }
  // Read here so that the destination below captures these rather than the project, which the
  // configuration cache cannot serialize.
  val path = project.path
  val ownFile = project.layout.buildDirectory.file("dependencyUpdates/partial.json")
  service.get().registerLegacy(ownFile)
  // A default action runs only while the build has declared nothing of its own, so one registered
  // here, ahead of the plugins that a project applies, identifies the configurations that a plugin
  // alone filled. Reading the dependencies later cannot tell the two apart, as any configuration
  // time reader of incoming.dependencies has by then run the actions that contribute them.
  // https://github.com/ben-manes/gradle-versions-plugin/issues/1028
  val filledByPlugin = ConcurrentHashMap.newKeySet<String>()
  project.configurations.configureEach { configuration ->
    // Only a configuration that dependencies are declared against accepts a default action, which
    // is every configuration that a plugin contributes to.
    if (configuration.isCanBeDeclared) {
      try {
        configuration.defaultDependencies { filledByPlugin.add(configuration.name) }
      } catch (e: GradleException) {
        // Gradle rejects a default action on a configuration that has taken part in a resolution,
        // so a project that applies this plugin after one did would fail to apply it at all. The
        // mark is a best effort attribution, which is worth losing for that configuration but not
        // the build. The configuration's own state cannot be checked for this: one observed by
        // another's resolution is still reported as unresolved, yet a default action on it is
        // rejected.
        project.logger.info(
          "Skipping the plugin mark for configuration ${project.absoluteProjectPath(configuration.name)}",
          e,
        )
      }
    }
  }
  val destination =
    outputFile ?: project.provider {
      // Realized once every project is configured, so the destination is read whatever order the
      // projects that publish and consume it were configured in. A build where no project
      // aggregates, as one that only contributes to another build's report, keeps its own.
      service.get().partialsDirectory?.get()?.file(partialFileName(path)) ?: ownFile.get()
    }
  val partial =
    tasks.register(PARTIAL_TASK_NAME, DependencyUpdatesPartialTask::class.java) { task ->
      task.outputFile.convention(destination)
      task.partialJson.set(
        // Realized after every project has been evaluated, so that the settings are read as last
        // configured and the container contains the configurations that late plugins added.
        project.provider {
          val parameters = service.get().resolve(project.path)
          val configurations =
            project.configurations
              .toList()
              .filter { it.isCanBeResolved }
              .filter { configuration ->
                parameters.filterConfigurations.isSatisfiedBy(configuration).also { checked ->
                  if (!checked) {
                    project.logger.info(
                      "Not checking configuration ${project.absoluteProjectPath(configuration.name)}, " +
                        "rejected by filterConfigurations",
                    )
                  }
                }
              }
          // The settings script's classpath contains the plugins its own plugins block declares,
          // which appear in no project's buildscript. It is reported once, from the project that
          // accumulates.
          // https://github.com/ben-manes/gradle-versions-plugin/issues/367
          val settingsContainer =
            // Compared by path rather than to project.rootProject, which isolated projects forbids.
            if (project.path == ":") {
              service.get().settingsConfigurations
            } else {
              null
            }
          // A project that declares no buildscript repository has nothing to resolve its script
          // classpath against, so querying it can only fail, and anything on it would be reported as
          // unresolvable. The settings classpath is kept either way, as it resolves against its own
          // repositories.
          // https://github.com/ben-manes/gradle-versions-plugin/issues/756
          val ownConfigurations =
            if (project.buildscript.repositories.isEmpty()) {
              emptyList()
            } else {
              project.buildscript.configurations.toList()
            }
          val buildscriptConfigurations =
            (ownConfigurations + settingsContainer.orEmpty())
              .filter { it.isCanBeResolved }

          val skipped = mutableListOf<SkippedInfo>()
          // Shared by both resolutions below, so the deprecation is warned once for the project.
          val onDeprecatedBoundRead = deprecatedBoundWarning(project.logger)
          // A module resolved by both passes below, as a project dependency also declared on the
          // buildscript classpath is, would otherwise be recorded twice.
          val candidates = LinkedHashSet<String>()
          val statuses =
            statusesOf(
              project,
              configurations,
              parameters,
              parameters.checkConstraints,
              filledByPlugin,
              nameDeclaringConfiguration = true,
              scriptClasspaths = false,
              skipped,
              onDeprecatedBoundRead,
              candidates,
            )
          val buildscriptStatuses =
            statusesOf(
              project,
              buildscriptConfigurations,
              parameters,
              parameters.checkBuildEnvironmentConstraints,
              // No default action is registered on the buildscript's configurations, so a project
              // configuration sharing a name with one must not discount its declared dependencies.
              filledByPlugin = emptySet(),
              // Every buildscript dependency is declared directly on the resolvable classpath
              // configuration, so naming it would describe a script rather than a plugin.
              nameDeclaringConfiguration = false,
              // The plugins block deposits its flattened markers only on these classpaths, so
              // only they read a dynamic required version as a bound.
              scriptClasspaths = true,
              skipped,
              onDeprecatedBoundRead,
              candidates,
              settingsContainer,
            )
          // Warned once the whole project's configurations and script classpaths are known, as the
          // two calls above share this list and a configuration skipped by each would otherwise be
          // warned about twice.
          warnSkipped(project, skipped)
          // Marked rather than left out here, so that `checkEmbeddedKotlin` is read from the task that
          // writes the report, as it is for the entries merged from an included build.
          val embeddedKotlin = EmbeddedKotlin.of(project)
          val marked = { status: PartialStatus, scriptClasspath: Boolean ->
            if (embeddedKotlin.pins(status, scriptClasspath)) status.copy(embeddedKotlin = true) else status
          }
          PartialResult(
            PartialResult.FORMAT_VERSION,
            project.buildTreePath,
            statuses.map { marked(it, false) },
            buildscriptStatuses.map { marked(it, true) },
            skipped,
            candidates.toList(),
            marksEmbeddedKotlin = true,
          ).toJson()
        },
      )
      task.partialJson.disallowChanges()
    }

  // Published once the project is configured, as whether it can have a variant at all depends on
  // the configurations that its plugins and build script create.
  val producer = Producer(partial, destination)
  if (project.state.executed) {
    publishResults(project, producer)
  } else {
    project.afterEvaluate { evaluated -> publishResults(evaluated, producer) }
  }
  return producer
}

/**
 * Publishes the project's statuses as an outgoing variant, which has no attributes where the
 * project declares no variant of its own, as one that exposes a local aar or jar file through its
 * `default` configuration does.
 *
 * Such a project is resolved by falling back to that configuration whatever the consumer asks for,
 * and Gradle drops the fallback as soon as the project declares an attributed variant. Attributing
 * this one would then serve the statuses to a consumer in place of the artifact it asked for, so it
 * is attributed only where the project has a variant of its own to be selected by instead. Whether
 * the `default` configuration contains an artifact yet is not checked, as a plugin may add its
 * publication from its own `afterEvaluate` and so after this runs, while the variants that decide
 * the fallback are declared as a plugin applies. The aggregate requests this configuration by name
 * rather than matching it by attributes, so it reads the statuses either way.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/1022
 */
private fun publishResults(
  project: Project,
  producer: Producer,
) {
  val configurations = project.configurations
  val fallback = configurations.findByName(Dependency.DEFAULT_CONFIGURATION)
  val publishesByFallback =
    fallback != null && fallback.isCanBeConsumed &&
      configurations.none { it.isCanBeConsumed && it.attributes.keySet().isNotEmpty() }

  configurations.consumable(ELEMENTS_CONFIGURATION) { configuration ->
    configuration.description = "The dependency update statuses of ${project.path}."
    if (!publishesByFallback) {
      configuration.attributes { attributes ->
        attributes.attribute(
          Category.CATEGORY_ATTRIBUTE,
          project.objects.named(Category::class.java, Category.VERIFICATION),
        )
        attributes.attribute(
          VerificationType.VERIFICATION_TYPE_ATTRIBUTE,
          project.objects.named(VerificationType::class.java, VERIFICATION_TYPE),
        )
      }
    }
    // The destination is published rather than the task's output, so that a query of this
    // variant's artifacts does not create the task. See the comment on Producer.
    configuration.outgoing.artifact(producer.destination) { artifact ->
      artifact.builtBy(producer.task)
    }
  }
}

/**
 * Publishes the results of the projects that this one aggregates alongside its own, so that a
 * consumer that declares the build by the coordinates it is substituted onto reads that build's
 * whole report rather than this project alone.
 *
 * Registered against the variant lazily, as it is created once the project is evaluated while the
 * producers are registered as the plugin is applied.
 */
private fun publishAggregatedResults(
  project: Project,
  publishable: FileCollection,
) {
  project.configurations
    .matching { it.name == ELEMENTS_CONFIGURATION }
    .configureEach { configuration ->
      configuration.outgoing.artifacts(project.provider { publishable.files }) { artifact ->
        artifact.builtBy(publishable)
      }
    }
}

/** Returns the statuses of the project's own configurations, skipping any that fail to resolve. */
private fun statusesOf(
  project: Project,
  configurations: List<Configuration>,
  parameters: ResolvedParameters,
  checkConstraints: Boolean,
  filledByPlugin: Set<String>,
  nameDeclaringConfiguration: Boolean,
  scriptClasspaths: Boolean,
  skipped: MutableList<SkippedInfo>,
  onDeprecatedBoundRead: () -> Unit,
  candidates: MutableSet<String>,
  settingsConfigurations: ConfigurationContainer? = null,
): List<PartialStatus> {
  if (configurations.isEmpty()) {
    return emptyList()
  }
  val resolver =
    Resolver(
      project,
      parameters.resolutionStrategy,
      checkConstraints = checkConstraints,
      rejectOutOfBounds = parameters.rejectOutOfBounds,
      preReleaseVersionIf = parameters.preReleaseVersionIf,
      exemptFromBuiltInChecksIf = parameters.exemptFromBuiltInChecksIf,
      settingsConfigurations = settingsConfigurations,
      onDeprecatedBoundRead = onDeprecatedBoundRead,
    )
  // Snapshotted for every configuration before the first resolution, as resolving one
  // configuration runs the lazy actions of the configurations it extends. A build that read the
  // dependencies while configuring has already run them, which the discount below corrects for.
  val declaredKeys =
    configurations.associateWith { runCatching { resolver.declaredKeys(it) }.getOrDefault(emptySet()) }
  val statuses =
    configurations.flatMap { configuration ->
      try {
        // Discounted after resolving, since resolving is what runs the default actions that fill
        // a configuration where every dependency came from a plugin.
        resolver.resolve(configuration, parameters.revision, nameDeclaringConfiguration, scriptClasspaths) {
          declaredKeys.getValue(configuration) - keysOf(configuration, filledByPlugin)
        }.filter { status ->
          // A status with no configuration name on it, as an ordinary declaration's is, is
          // kept whatever the filter rejects.
          status.configurations.isEmpty() ||
            status.configurations.any { parameters.filterDeclaredConfigurations.isSatisfiedBy(it) }
        }.map { it.toPartialStatus() }
      } catch (e: Exception) {
        val reason =
          generateSequence(e as Throwable) { it.cause }.take(MAX_FAILURE_CAUSES).joinToString("; ") { it.toString() }
        // The default-visible warning is grouped and emitted once the project's whole set of skipped
        // configurations is known, so only the stack trace is logged here.
        project.logger.info("Skipping configuration ${project.absoluteProjectPath(configuration.name)}", e)
        skipped.add(SkippedInfo(configuration.name, reason))
        emptyList()
      }
    }
  // Locked while draining, since a synchronized set synchronizes only its own methods and copying
  // one into a set iterates it.
  synchronized(resolver.candidates) {
    candidates.addAll(resolver.candidates)
  }
  return statuses
}

/**
 * Warns once per distinct reason among the project's skipped configurations, naming the
 * configurations it dropped, rather than once per configuration: a build with many configurations
 * sharing one failing resolutionStrategy would otherwise flood the log with one line each.
 */
private fun warnSkipped(
  project: Project,
  skipped: List<SkippedInfo>,
) {
  for ((reason, group) in skipped.groupBy { it.reason }) {
    val noun = if (group.size == 1) "configuration" else "configurations"
    val names = group.joinToString(", ") { "'${it.name}'" }
    project.logger.warn(
      "Skipping $noun $names in ${projectsLabel(listOf(project.buildTreePath))}: " +
        reason.lineSequence().first(),
    )
  }
}
