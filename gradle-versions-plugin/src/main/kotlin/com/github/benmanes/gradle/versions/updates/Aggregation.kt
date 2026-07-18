package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.VerificationType
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.TaskProvider

internal const val PARTIAL_TASK_NAME = "dependencyUpdatesPartial"
private const val ELEMENTS_CONFIGURATION = "dependencyUpdatesElements"
private const val AGGREGATION_CONFIGURATION = "dependencyUpdatesAggregation"
private const val RESULTS_CONFIGURATION = "aggregateDependencyUpdatesResults"
private const val VERIFICATION_TYPE = "dependency-updates"
private const val AGGREGATE_PROPERTY = "com.github.benmanes.versions.aggregate"

/** Returns whether the per-project producer and root accumulator topology is opted into. */
internal fun isAggregationEnabled(): Boolean = System.getProperty(AGGREGATE_PROPERTY) == "true"

/** The task settings that the per-project producers need, frozen once the root project evaluates. */
internal class DependencyUpdatesParameters {
  var revision: String = "milestone"
  var filterConfigurations: Spec<Configuration> = Spec { true }
  var resolutionStrategy: Action<in ResolutionStrategyWithCurrent>? = null
  var checkConstraints: Boolean = false
  var checkBuildEnvironmentConstraints: Boolean = false
}

/** Registers the per-project producers and wires their results into the accumulator task. */
internal fun registerAggregation(
  project: Project,
  accumulator: TaskProvider<DependencyUpdatesTask>,
) {
  val parameters = DependencyUpdatesParameters()

  // Registered before the producers so that the root task's configuration blocks have run, and its
  // values are frozen, by the time any project snapshots its configurations.
  project.afterEvaluate { accumulator.get().freezeInto(parameters, project) }
  project.allprojects { producer -> registerProducer(producer, parameters) }

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
  for (aggregated in project.allprojects) {
    project.dependencies.add(
      AGGREGATION_CONFIGURATION,
      project.dependencies.project(mapOf("path" to aggregated.path)),
    )
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
}

/** Registers the task and outgoing variant that publish a single project's statuses. */
private fun registerProducer(
  project: Project,
  parameters: DependencyUpdatesParameters,
) {
  if (project.tasks.names.contains(PARTIAL_TASK_NAME)) {
    return
  }
  val partial =
    project.tasks.register(PARTIAL_TASK_NAME, DependencyUpdatesPartialTask::class.java) { task ->
      task.outputFile.convention(
        project.layout.buildDirectory.file("dependencyUpdates/partial.json"),
      )
    }

  project.afterEvaluate {
    // Snapshot the container rather than holding a live view, which plugins may add to later.
    val configurations =
      project.configurations
        .toList()
        .filter { it.isCanBeResolved && parameters.filterConfigurations.isSatisfiedBy(it) }
    val buildscriptConfigurations =
      project.buildscript.configurations
        .toList()
        .filter { it.isCanBeResolved }

    partial.configure { task ->
      task.partialJson.set(
        project.provider {
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
