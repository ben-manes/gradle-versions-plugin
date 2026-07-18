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

internal const val PARTIAL_TASK_NAME = "dependencyUpdatesPartial"
private const val ELEMENTS_CONFIGURATION = "dependencyUpdatesElements"
private const val AGGREGATION_CONFIGURATION = "dependencyUpdatesAggregation"
private const val RESULTS_CONFIGURATION = "aggregateDependencyUpdatesResults"
private const val PARAMETERS_SERVICE = "dependencyUpdatesParameters"
private const val VERIFICATION_TYPE = "dependency-updates"
private const val AGGREGATE_PROPERTY = "com.github.benmanes.versions.aggregate"

/** Shared so that an unconfigured task's filter is recognizable by identity. */
internal val ALL_CONFIGURATIONS = Spec<Configuration> { true }

/** Returns whether the per-project producer and root accumulator topology is opted into. */
internal fun isAggregationEnabled(): Boolean = System.getProperty(AGGREGATE_PROPERTY) == "true"

/** Returns whether isolated projects is enabled, which forbids configuring the other projects. */
internal fun isIsolatedProjectsEnabled(project: Project): Boolean =
  runCatching {
    (project.gradle.startParameter as StartParameterInternal).isolatedProjects.get()
  }.getOrDefault(false)

/** The task settings that the per-project producers need, frozen once the owning project evaluates. */
internal class DependencyUpdatesParameters {
  var revision: String = "milestone"
  var filterConfigurations: Spec<Configuration> = ALL_CONFIGURATIONS
  var resolutionStrategy: Action<in ResolutionStrategyWithCurrent>? = null
  var checkConstraints: Boolean = false
  var checkBuildEnvironmentConstraints: Boolean = false

  /** Returns whether the owning task was left untouched, so that an ancestor's settings apply. */
  internal val isDefault: Boolean
    get() =
      revision == "milestone" &&
        filterConfigurations === ALL_CONFIGURATIONS &&
        resolutionStrategy == null &&
        !checkConstraints &&
        !checkBuildEnvironmentConstraints
}

/**
 * Holds the settings frozen by each project that applies the plugin.
 *
 * A producer reads its settings from here while its input is realized, which is the only channel
 * that isolated projects permits between the project that owns the task settings and the projects
 * that resolve with them.
 */
internal abstract class DependencyUpdatesParametersService :
  BuildService<BuildServiceParameters.None> {
  private val byPath = ConcurrentHashMap<String, DependencyUpdatesParameters>()

  /** Returns the settings that the given project publishes, creating them on first application. */
  fun of(path: String): DependencyUpdatesParameters = byPath.computeIfAbsent(path) { DependencyUpdatesParameters() }

  /** Returns the settings of the nearest ancestor that configured its task, else the defaults. */
  fun resolve(path: String): DependencyUpdatesParameters {
    var ancestor: String? = path
    while (ancestor != null) {
      val parameters = byPath[ancestor]
      if (parameters != null && !parameters.isDefault) {
        return parameters
      }
      ancestor = if (ancestor == ":") null else ancestor.substringBeforeLast(':').ifEmpty { ":" }
    }
    return of(path)
  }
}

/** Registers the per-project producers and wires their results into the accumulator task. */
internal fun registerAggregation(
  project: Project,
  accumulator: TaskProvider<DependencyUpdatesTask>,
) {
  val service =
    project.gradle.sharedServices
      .registerIfAbsent(PARAMETERS_SERVICE, DependencyUpdatesParametersService::class.java) { }
  val parameters = service.get().of(project.path)
  project.afterEvaluate { accumulator.get().freezeInto(parameters, project) }

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

  accumulator.configure { task ->
    task.projectPath = project.path
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

  if (isIsolatedProjectsEnabled(project)) {
    // Isolated projects forbids registering a task in another project, so each applies the plugin
    // and the results are collected as artifacts of a project dependency instead. A project that
    // does not apply the plugin has no producer and is omitted, which only a settings plugin could
    // fix; gradle.lifecycle.beforeProject is never invoked for a callback added by a project.
    // https://docs.gradle.org/current/userguide/isolated_projects.html
    registerProducer(project, service)
    for (aggregated in project.allprojects) {
      project.dependencies.add(
        AGGREGATION_CONFIGURATION,
        project.dependencies.project(mapOf("path" to aggregated.path)),
      )
    }
  } else {
    // Wired as task outputs rather than as project dependencies, which would drop every project
    // that shares a group and name with a sibling as a module conflict.
    project.allprojects { aggregated ->
      val partial = registerProducer(aggregated, service)
      accumulator.configure { task -> task.partialResults.from(partial.flatMap { it.outputFile }) }
    }
  }
}

/** Registers the task and outgoing variant that publish a single project's statuses. */
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
        // Realized after every project has been evaluated, so that the settings are frozen and the
        // container holds the configurations that late plugins and afterEvaluate blocks added.
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
  parameters: DependencyUpdatesParameters,
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
