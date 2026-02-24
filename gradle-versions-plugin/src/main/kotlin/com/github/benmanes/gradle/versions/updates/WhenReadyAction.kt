package com.github.benmanes.gradle.versions.updates

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.TaskProvider

/**
 * Handles the [org.gradle.api.execution.TaskExecutionGraph.whenReady] callback for
 * [DependencyUpdatesTask]. This logic is in a separate class so that the Kotlin compiler
 * emits the lambda methods (especially the [ACCEPT_ALL_CONFIGURATIONS] default and the
 * callback capturing [Project]) on this class's bytecode rather than on [DependencyUpdatesTask].
 *
 * Gradle's configuration cache inspects task class bytecode and flags any lambda method
 * that references disallowed types like Configuration or Project. By moving these
 * lambdas here, the task class stays clean.
 *
 * The callback is registered during project evaluation (from [com.github.benmanes.gradle.versions.VersionsPlugin.apply])
 * so that it fires after ALL task configuration actions (including `configureEach` from build scripts)
 * have completed. The [TaskProvider] is only resolved (`get()`) inside the callback, which runs
 * after the task graph is finalized and the task has been fully configured.
 */
internal object WhenReadyAction {
  fun register(
    taskProvider: TaskProvider<DependencyUpdatesTask>,
    project: Project,
  ) {
    project.gradle.taskGraph.whenReady { taskGraph ->
      val task = taskProvider.get()
      if (taskGraph.hasTask(task)) {
        val storageKey = task.path
        @Suppress("UNCHECKED_CAST")
        val filter = (task.filterConfigurations as? Spec<Configuration>) ?: ACCEPT_ALL_CONFIGURATIONS
        val projectConfigs =
          project.allprojects.map { p ->
            ProjectConfigurations(
              ProjectContext.from(p),
              p.configurations.matching(filter).toSet(),
            )
          }
        val buildscriptConfigs =
          project.allprojects.map { p ->
            ProjectConfigurations(
              ProjectContext.from(p),
              p.buildscript.configurations.toSet(),
            )
          }
        DependencyUpdatesTask.executionDataCache[storageKey] =
          DependencyUpdatesTask.ExecutionData(
            projectConfigs = projectConfigs,
            buildscriptConfigs = buildscriptConfigs,
            outputFormatterArgument = task.outputFormatterArgument,
            resolutionStrategyAction = task.resolutionStrategyAction,
          )
        task.clearConfigurationTimeState()
      }
    }
  }
}
