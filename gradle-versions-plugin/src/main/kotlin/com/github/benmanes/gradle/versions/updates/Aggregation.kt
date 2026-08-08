package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.claims
import com.github.benmanes.gradle.versions.reporter.projectsLabel
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.VerificationType
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.internal.StartParameterInternal
import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.TaskProvider
import java.util.concurrent.ConcurrentHashMap

// Not prefixed with "dependencyUpdates": a project with only a producer would otherwise let
// Gradle's task abbreviation match "dependencyUpdates" to it and silently succeed with no report.
internal const val PARTIAL_TASK_NAME = "partialDependencyUpdates"
private const val ELEMENTS_CONFIGURATION = "dependencyUpdatesElements"
private const val AGGREGATION_CONFIGURATION = "dependencyUpdatesAggregation"
private const val PARAMETERS_SERVICE = "dependencyUpdatesParameters"
private const val VERIFICATION_TYPE = "dependency-updates"

/** The number of causes joined into a skipped configuration's reason, matching DependencyStatus. */
private const val MAX_FAILURE_CAUSES = 20

/** The filter applied when a task leaves the configurations unrestricted. */
internal val ALL_CONFIGURATIONS = Spec<Configuration> { true }

/** The filter applied when a task leaves the declared configurations unrestricted. */
internal val ALL_DECLARED_CONFIGURATIONS = Spec<String> { true }

/** Returns whether isolated projects is enabled, which forbids configuring the other projects. */
internal fun isIsolatedProjectsEnabled(project: Project): Boolean =
  runCatching {
    (project.gradle.startParameter as StartParameterInternal).isolatedProjects.get()
  }.getOrDefault(false)

/** The task settings that a project's producer reads while its input is realized; null is unset. */
internal class DependencyUpdatesParameters {
  var revision: String? = null

  @Transient
  var filterConfigurations: Spec<Configuration>? = null

  @Transient
  var filterDeclaredConfigurations: Spec<String>? = null

  @Transient
  var resolutionStrategy: Action<in ResolutionStrategyWithCurrent>? = null

  /** Distinguishes a strategy that was explicitly cleared from one that was never set. */
  var resolutionStrategySet: Boolean = false
  var checkConstraints: Boolean? = null
  var checkBuildEnvironmentConstraints: Boolean? = null
}

/**
 * Holds the settings of the task of each project that applies the plugin.
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
  var settingsConfigurations: List<Configuration> = emptyList()

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

  /** Publishes the settings of the given project's task to the projects that resolve with them. */
  fun register(
    path: String,
    parameters: DependencyUpdatesParameters,
  ) {
    byPath[path] = parameters
  }

  /** Returns the effective settings, taking each property from the nearest ancestor that set it. */
  fun resolve(path: String): ResolvedParameters {
    val chain =
      generateSequence(path) { if (it == ":") null else it.substringBeforeLast(':').ifEmpty { ":" } }
        .mapNotNull { byPath[it] }
        .toList()
    return ResolvedParameters(
      revision =
        (System.getProperties()["revision"] as String?)
          ?: chain.firstNotNullOfOrNull { it.revision } ?: "milestone",
      filterConfigurations =
        chain.firstNotNullOfOrNull { it.filterConfigurations } ?: ALL_CONFIGURATIONS,
      filterDeclaredConfigurations =
        chain.firstNotNullOfOrNull { it.filterDeclaredConfigurations } ?: ALL_DECLARED_CONFIGURATIONS,
      resolutionStrategy = chain.firstOrNull { it.resolutionStrategySet }?.resolutionStrategy,
      checkConstraints = chain.firstNotNullOfOrNull { it.checkConstraints } ?: false,
      checkBuildEnvironmentConstraints =
        chain.firstNotNullOfOrNull { it.checkBuildEnvironmentConstraints } ?: false,
    )
  }
}

/** The settings that apply to a single project's producer. */
internal class ResolvedParameters(
  val revision: String,
  val filterConfigurations: Spec<Configuration>,
  val filterDeclaredConfigurations: Spec<String>,
  val resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?,
  val checkConstraints: Boolean,
  val checkBuildEnvironmentConstraints: Boolean,
)

/** Registers the per-project producers and wires their results into the accumulator task. */
internal fun registerAggregation(
  project: Project,
  accumulator: TaskProvider<DependencyUpdatesTask>,
) {
  val service = parametersService(project.gradle)
  accumulator.configure { task -> service.get().register(project.path, task.parameters) }
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
  // holds only the project dependencies that the plugin declares and has no lock state of its own.
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
  // configuration is named on the way through, as it is for the projects below, so that a project
  // declaring no variant of its own is read by name rather than fallen back to its `default`
  // configuration, whose artifacts are the project's own and not a partial result. Every module
  // dependency is named rather than the project ones alone, as an included build is declared by
  // its coordinates and substituted onto its project only once the graph resolves.
  aggregation.get().dependencies.all { dependency ->
    results.dependencies.add(
      if (dependency is ModuleDependency) {
        dependency.copy().apply { targetConfiguration = ELEMENTS_CONFIGURATION }
      } else {
        dependency
      },
    )
  }

  // Reading the paths across projects is permitted under isolated projects, unlike configuring. A
  // project with no build script cannot apply the plugin there, so naming it in the completeness
  // warning would report what the user has no way to act on.
  val aggregatedPaths =
    project.allprojects.filter { it.buildFile.exists() }.map { it.path }.toSet()

  val partialsDirectory = project.layout.buildDirectory.dir("dependencyUpdates/partials")
  accumulator.configure { task ->
    task.projectPath = project.path
    task.aggregatedProjectPaths = aggregatedPaths
    task.projectDirectory.set(project.layout.projectDirectory)
    task.partialResults.from(
      results.incoming
        .artifactView { view ->
          view.componentFilter { id -> id is ProjectComponentIdentifier }
          view.lenient(true)
        }.files,
    )
  }

  // Declared for every project so that computing the aggregate's dependencies configures each of
  // them, which configure on demand skips when the task is invoked by its path rather than by name.
  // The producer's configuration is named rather than matched by its attributes, so that a project
  // which publishes no variant is skipped instead of falling back to its `default` configuration,
  // whose artifacts are the project's own and not a partial result to read the report from.
  for (aggregated in project.allprojects) {
    project.dependencies.add(
      AGGREGATION_CONFIGURATION,
      project.dependencies.project(
        mapOf("path" to aggregated.path, "configuration" to ELEMENTS_CONFIGURATION),
      ),
    )
  }

  if (isIsolatedProjectsEnabled(project)) {
    // Isolated projects forbids registering a task in another project, so each applies the plugin
    // and the results are collected as artifacts of those dependencies alone. A project that does
    // not apply the plugin has no producer and is omitted, which only a settings plugin could fix;
    // gradle.lifecycle.beforeProject is never invoked for a callback added by a project.
    // https://docs.gradle.org/current/userguide/isolated_projects.html
    //
    // The destination is published for the producers that those projects register, as a settings
    // plugin reaches a project that has no build script and it would otherwise be given a build
    // directory to hold nothing else. Only the project at the root path publishes, as a mid tree
    // aggregate reads the projects it does not own as variant artifacts, which never cared where
    // the file lives. Compared by path rather than to project.rootProject, which is forbidden here.
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
    registerProducer(project, service)
  } else {
    // Swept only here, as the artifacts are all that name the projects under isolated projects and
    // they omit the ones that conflict resolution merges away, whose results their own report still
    // reads from where its producer wrote them. A result that no project of the build still owns is
    // left behind there rather than risk removing one in use, and is never read, as the results are
    // wired by path rather than discovered.
    accumulator.configure { task -> task.partialsDirectory.set(partialsDirectory) }
    // The results are wired as task outputs too, as module conflict resolution would otherwise drop
    // every project that shares a group and name with a sibling from the artifacts.
    project.allprojects { aggregated ->
      // A project that another copy of the plugin claimed holds a producer of that copy's type,
      // which this one cannot wire to its accumulator. That copy reports the project instead.
      if (claims(aggregated)) {
        // Written under the project that aggregates rather than each project's own build
        // directory, so that a project which exists only to hold a nested include gains no build
        // directory of its own. Passed rather than published, as this project registers the
        // producer itself and so needs no channel to reach it.
        // https://github.com/ben-manes/gradle-versions-plugin/issues/1040
        val outputFile = partialsDirectory.map { it.file(partialFileName(aggregated.path)) }
        val partial = registerProducer(aggregated, service, outputFile)
        val legacy = aggregated.layout.buildDirectory.file("dependencyUpdates/partial.json")
        accumulator.configure { task ->
          task.partialResults.from(partial.flatMap { it.outputFile })
          task.legacyPartials.from(legacy)
        }
      }
    }
  }
}

/** Registers the task and outgoing variant that publish a single project's statuses. */
internal fun registerProducer(project: Project): TaskProvider<DependencyUpdatesPartialTask> =
  registerProducer(project, parametersService(project.gradle))

/** Publishes the settings script's classpath to the project that accumulates the report. */
internal fun publishSettingsClasspath(
  gradle: Gradle,
  configurations: List<Configuration>,
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

private fun registerProducer(
  project: Project,
  service: Provider<DependencyUpdatesParametersService>,
  outputFile: Provider<RegularFile>? = null,
): TaskProvider<DependencyUpdatesPartialTask> {
  val tasks = project.tasks
  if (tasks.names.contains(PARTIAL_TASK_NAME)) {
    return tasks.named(PARTIAL_TASK_NAME, DependencyUpdatesPartialTask::class.java)
  }
  // Read here so that the destination below captures these rather than the project, which the
  // configuration cache cannot serialize.
  val path = project.path
  val ownFile = project.layout.buildDirectory.file("dependencyUpdates/partial.json")
  service.get().registerLegacy(ownFile)
  // A default action runs only while the build has declared nothing of its own, so one registered
  // here, ahead of the plugins that a project applies, names the configurations that a plugin alone
  // filled. Reading the dependencies later cannot tell the two apart, as any configuration time
  // reader of incoming.dependencies has by then run the actions that contribute them.
  // https://github.com/ben-manes/gradle-versions-plugin/issues/1028
  val filledByPlugin = ConcurrentHashMap.newKeySet<String>()
  project.configurations.configureEach { configuration ->
    // Only a configuration that dependencies are declared against accepts a default action, which
    // is every configuration that a plugin contributes to.
    if (configuration.isCanBeDeclared) {
      try {
        configuration.defaultDependencies { filledByPlugin.add(configuration.name) }
      } catch (e: GradleException) {
        // A configuration that has taken part in a resolution refuses a default action, so a
        // project that applies this plugin after one did would fail to apply it at all. The mark
        // is a best effort attribution, which is worth losing for that configuration but not the
        // build. Its state does not answer this, as a configuration observed by another's
        // resolution refuses one while still reporting itself as unresolved.
        project.logger.info(
          "Skipping the plugin mark for configuration ${project.path}:${configuration.name}",
          e,
        )
      }
    }
  }
  val partial =
    tasks.register(PARTIAL_TASK_NAME, DependencyUpdatesPartialTask::class.java) { task ->
      task.outputFile.convention(
        outputFile ?: project.provider {
          // Realized once every project is configured, so the destination is read whatever order
          // the projects that publish and consume it were configured in. A build where no project
          // aggregates, as one that only contributes to another build's report, keeps its own.
          service.get().partialsDirectory?.get()?.file(partialFileName(path)) ?: ownFile.get()
        },
      )
      task.partialJson.set(
        // Realized after every project has been evaluated, so that the settings are read as last
        // configured and the container holds the configurations that late plugins added.
        project.provider {
          val parameters = service.get().resolve(project.path)
          val configurations =
            project.configurations
              .toList()
              .filter { it.isCanBeResolved && parameters.filterConfigurations.isSatisfiedBy(it) }
          // The settings script's classpath holds the plugins its own plugins block declares, which
          // no project's buildscript does. It is reported once, from the project that accumulates.
          // https://github.com/ben-manes/gradle-versions-plugin/issues/367
          val settingsConfigurations =
            // Compared by path rather than to project.rootProject, which isolated projects forbids.
            if (project.path == ":") {
              service.get().settingsConfigurations
            } else {
              emptyList()
            }
          // A project that declares no buildscript repository has nothing to resolve its script
          // classpath against, so querying it can only fail. Gradle constrains every classpath to
          // its own log4j version, which such a project would otherwise report as unresolvable.
          // The settings classpath is kept either way, as it resolves against its own repositories.
          // https://github.com/ben-manes/gradle-versions-plugin/issues/756
          val ownConfigurations =
            if (project.buildscript.repositories.isEmpty()) {
              emptyList()
            } else {
              project.buildscript.configurations.toList()
            }
          val buildscriptConfigurations =
            (ownConfigurations + settingsConfigurations)
              .filter { it.isCanBeResolved }

          val skipped = mutableListOf<SkippedInfo>()
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
              // only they recover a catalog plugin constraint.
              scriptClasspaths = true,
              skipped,
            )
          // Warned once the whole project's configurations and script classpaths are known, as the
          // two calls above share this list and a configuration skipped by each would otherwise be
          // warned about twice.
          warnSkipped(project, skipped)
          PartialResult(
            PartialResult.FORMAT_VERSION,
            project.path,
            statuses,
            buildscriptStatuses,
            skipped,
          ).toJson()
        },
      )
      task.partialJson.disallowChanges()
    }

  // Published once the project is configured, as whether it may carry a variant at all depends on
  // the configurations that its plugins and build script create.
  if (project.state.executed) {
    publishResults(project, partial)
  } else {
    project.afterEvaluate { evaluated -> publishResults(evaluated, partial) }
  }
  return partial
}

/**
 * Publishes the project's statuses as an outgoing variant, which carries no attributes where the
 * project declares no variant of its own, as one that exposes a local aar or jar file through its
 * `default` configuration does.
 *
 * Such a project is resolved by falling back to that configuration whatever the consumer asks for,
 * and Gradle drops the fallback as soon as the project declares an attributed variant. Attributing
 * this one would then serve the statuses to a consumer in place of the artifact it asked for, so it
 * is attributed only where the project has a variant of its own to be selected by instead. Whether
 * the `default` configuration holds an artifact yet is not asked, as a plugin may add its
 * publication from its own `afterEvaluate` and so after this runs, while the variants that decide
 * the fallback are declared as a plugin applies. The aggregate names this configuration rather than
 * matching it by attributes, so it reads the statuses either way.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/1022
 */
private fun publishResults(
  project: Project,
  partial: TaskProvider<DependencyUpdatesPartialTask>,
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
    configuration.outgoing.artifact(partial.flatMap { it.outputFile })
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
): List<PartialStatus> {
  if (configurations.isEmpty()) {
    return emptyList()
  }
  val resolver = Resolver(project, parameters.resolutionStrategy, checkConstraints)
  // Snapshotted for every configuration before the first resolution, as resolving one
  // configuration runs the lazy actions of the configurations it extends. A build that read the
  // dependencies while configuring has already run them, which the discount below corrects for.
  val declaredKeys =
    configurations.associateWith { runCatching { resolver.declaredKeys(it) }.getOrDefault(emptySet()) }
  return configurations.flatMap { configuration ->
    try {
      // Discounted after resolving, which is what runs the default actions that name the
      // configurations whose every dependency a plugin contributed.
      resolver.resolve(configuration, parameters.revision, nameDeclaringConfiguration, scriptClasspaths) {
        declaredKeys.getValue(configuration) - keysOf(configuration, filledByPlugin)
      }.filter { status ->
        // A status that names no configuration, as an ordinary declaration's does, is kept
        // whatever the filter rejects.
        status.configurations.isEmpty() ||
          status.configurations.any { parameters.filterDeclaredConfigurations.isSatisfiedBy(it) }
      }.map { it.toPartialStatus() }
    } catch (e: Exception) {
      val reason =
        generateSequence(e as Throwable) { it.cause }.take(MAX_FAILURE_CAUSES).joinToString("; ") { it.toString() }
      // The default-visible warning is grouped and emitted once the project's whole set of skipped
      // configurations is known, so only the stack trace is logged here.
      project.logger.info("Skipping configuration ${project.path}:${configuration.name}", e)
      skipped.add(SkippedInfo(configuration.name, reason))
      emptyList()
    }
  }
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
      "Skipping $noun $names in ${projectsLabel(listOf(project.path))}: " + reason.lineSequence().first(),
    )
  }
}
