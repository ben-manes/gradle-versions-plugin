package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.VerificationType
import org.gradle.api.internal.StartParameterInternal
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
private const val RESULTS_CONFIGURATION = "aggregateDependencyUpdatesResults"
private const val PARAMETERS_SERVICE = "dependencyUpdatesParameters"
private const val VERIFICATION_TYPE = "dependency-updates"

/** The filter applied when a task leaves the configurations unrestricted. */
internal val ALL_CONFIGURATIONS = Spec<Configuration> { true }

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
  val resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?,
  val checkConstraints: Boolean,
  val checkBuildEnvironmentConstraints: Boolean,
)

/** Registers the per-project producers and wires their results into the accumulator task. */
internal fun registerAggregation(
  project: Project,
  accumulator: TaskProvider<DependencyUpdatesTask>,
) {
  val service = parametersService(project)
  accumulator.configure { task -> service.get().register(project.path, task.parameters) }
  // Realizes the task, so that a configuration block on a task that nothing else realizes is still
  // applied before the producers read the settings.
  project.afterEvaluate { accumulator.get() }

  val aggregation =
    project.configurations.dependencyScope(AGGREGATION_CONFIGURATION) { configuration ->
      configuration.description = "Collects the projects to aggregate dependency updates from."
    }
  val results =
    project.configurations.resolvable(RESULTS_CONFIGURATION) { configuration ->
      configuration.description = "Resolves the dependency update results to aggregate."
      configuration.extendsFrom(aggregation.get())
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

  // Reading the paths across projects is permitted under isolated projects, unlike configuring. A
  // project with no build script cannot apply the plugin there, so naming it in the completeness
  // warning would report what the user has no way to act on.
  val aggregatedPaths =
    project.allprojects.filter { it.buildFile.exists() }.map { it.path }.toSet()
  accumulator.configure { task ->
    task.projectPath = project.path
    task.aggregatedProjectPaths = aggregatedPaths
    task.projectDirectory.set(project.layout.projectDirectory)
    task.partialResults.from(
      results.map { configuration ->
        configuration.incoming
          .artifactView { view ->
            view.componentFilter { id -> id is ProjectComponentIdentifier }
            view.lenient(true)
          }.files
      },
    )
  }

  // Declared for every project so that computing the aggregate's dependencies configures each of
  // them, which configure on demand skips when the task is invoked by its path rather than by name.
  for (aggregated in project.allprojects) {
    project.dependencies.add(
      AGGREGATION_CONFIGURATION,
      project.dependencies.project(mapOf("path" to aggregated.path)),
    )
  }

  if (isIsolatedProjectsEnabled(project)) {
    // Isolated projects forbids registering a task in another project, so each applies the plugin
    // and the results are collected as artifacts of those dependencies alone. A project that does
    // not apply the plugin has no producer and is omitted, which only a settings plugin could fix;
    // gradle.lifecycle.beforeProject is never invoked for a callback added by a project.
    // https://docs.gradle.org/current/userguide/isolated_projects.html
    registerProducer(project, service)
  } else {
    // The results are wired as task outputs too, as module conflict resolution would otherwise drop
    // every project that shares a group and name with a sibling from the artifacts.
    project.allprojects { aggregated ->
      val partial = registerProducer(aggregated, service)
      accumulator.configure { task -> task.partialResults.from(partial.flatMap { it.outputFile }) }
    }
  }
}

/** Registers the task and outgoing variant that publish a single project's statuses. */
internal fun registerProducer(project: Project): TaskProvider<DependencyUpdatesPartialTask> =
  registerProducer(project, parametersService(project))

/** Returns the build's shared parameters service, registering it if this is the first use. */
private fun parametersService(project: Project): Provider<DependencyUpdatesParametersService> =
  project.gradle.sharedServices
    .registerIfAbsent(PARAMETERS_SERVICE, DependencyUpdatesParametersService::class.java) { }

private fun registerProducer(
  project: Project,
  service: Provider<DependencyUpdatesParametersService>,
): TaskProvider<DependencyUpdatesPartialTask> {
  val tasks = project.tasks
  if (tasks.names.contains(PARTIAL_TASK_NAME)) {
    return tasks.named(PARTIAL_TASK_NAME, DependencyUpdatesPartialTask::class.java)
  }
  val partial =
    tasks.register(PARTIAL_TASK_NAME, DependencyUpdatesPartialTask::class.java) { task ->
      task.outputFile.convention(
        project.layout.buildDirectory.file("dependencyUpdates/partial.json"),
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
          val buildscriptConfigurations =
            project.buildscript.configurations
              .toList()
              .filter { it.isCanBeResolved }

          PartialResult(
            PartialResult.FORMAT_VERSION,
            project.path,
            statusesOf(project, configurations, parameters, parameters.checkConstraints),
            statusesOf(
              project,
              buildscriptConfigurations,
              parameters,
              parameters.checkBuildEnvironmentConstraints,
            ),
          ).toJson()
        },
      )
      task.partialJson.disallowChanges()
    }

  project.configurations.consumable(ELEMENTS_CONFIGURATION) { configuration ->
    configuration.description = "The dependency update statuses of ${project.path}."
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
    configuration.outgoing.artifact(partial.flatMap { it.outputFile })
  }
  return partial
}

/** Returns the statuses of the project's own configurations, skipping any that fail to resolve. */
private fun statusesOf(
  project: Project,
  configurations: List<Configuration>,
  parameters: ResolvedParameters,
  checkConstraints: Boolean,
): List<PartialStatus> {
  if (configurations.isEmpty()) {
    return emptyList()
  }
  val resolver = Resolver(project, parameters.resolutionStrategy, checkConstraints)
  return configurations.flatMap { configuration ->
    try {
      resolver.resolve(configuration, parameters.revision).map { it.toPartialStatus() }
    } catch (e: Exception) {
      project.logger.info("Skipping configuration ${project.path}:${configuration.name}", e)
      emptyList()
    }
  }
}
