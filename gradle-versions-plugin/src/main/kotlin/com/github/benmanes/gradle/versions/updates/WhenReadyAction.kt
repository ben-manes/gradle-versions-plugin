package com.github.benmanes.gradle.versions.updates

import groovy.lang.Closure
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.specs.Spec

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
 * have completed. Tasks are discovered via [org.gradle.api.execution.TaskExecutionGraph.allTasks],
 * which includes every [DependencyUpdatesTask] in the graph regardless of its name.
 *
 * In a multi-project build the plugin is applied per-project, but only a single callback
 * is registered per Gradle instance (guarded by an extra property on the root project). The callback iterates all tasks in the graph and caches each one
 * using [org.gradle.api.Task.getProject] to obtain the correct project scope. Accessing
 * `task.project` here is safe because `whenReady` runs during the configuration phase, before
 * configuration-cache serialization — CC restrictions only apply to task execution.
 */
internal object WhenReadyAction {
  private const val REGISTERED_PROPERTY = "com.github.benmanes.gradle.versions.whenReadyRegistered"

  fun register(project: Project) {
    // Guard against registering multiple whenReady callbacks in multi-project builds.
    // The plugin is applied per-project, but the callback iterates ALL tasks in the
    // graph, so a single registration per Gradle instance is sufficient.
    // Uses rootProject extra properties because Gradle.getExtensions() is unavailable
    // in Gradle versions prior to 8.x.
    val rootProject = project.rootProject
    if (rootProject.extensions.extraProperties.has(REGISTERED_PROPERTY)) {
      return
    }
    rootProject.extensions.extraProperties.set(REGISTERED_PROPERTY, true)

    project.gradle.taskGraph.whenReady { taskGraph ->
      for (task in taskGraph.allTasks) {
        if (task is DependencyUpdatesTask) {
          cacheExecutionData(task)
        }
      }
    }
  }

  private fun cacheExecutionData(task: DependencyUpdatesTask) {
    // task.project is safe here — we're in a whenReady callback (configuration phase),
    // before CC serialization, not during task execution.
    val project = task.project

    // Ensure task properties are set even when the plugin wasn't applied to this project
    // directly (e.g., a custom task registered at root while the plugin is applied to subprojects).
    if (task.taskProjectPath.isEmpty()) {
      task.taskProjectDir = project.projectDir
      task.taskProjectPath = project.path
    }

    val storageKey = task.path

    @Suppress("UNCHECKED_CAST")
    val filter =
      when (val raw = task.filterConfigurations) {
        is Spec<*> -> raw as Spec<Configuration>
        is Closure<*> -> Spec<Configuration> { config -> raw.call(config) as Boolean }
        else -> ACCEPT_ALL_CONFIGURATIONS
      }
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

    // Resolve dependencies at configuration time. In Gradle 9.x with configuration cache,
    // project services (ConfigurationContainer, DependencyHandler) are closed after the
    // configuration phase, making it impossible to create detached configurations or resolve
    // dependencies at execution time.
    val evaluator =
      DependencyUpdates(
        projectConfigs,
        buildscriptConfigs,
        task.taskProjectDir,
        task.taskProjectPath,
        task.resolutionStrategyAction,
        task.revision,
        task.outputFormatterArgument,
        task.outputDir,
        task.reportfileName,
        task.checkForGradleUpdate,
        task.gradleVersionsApiBaseUrl,
        task.gradleReleaseChannel,
        task.checkConstraints,
        task.checkBuildEnvironmentConstraints,
      )
    val (projectStatuses, buildscriptStatuses) = evaluator.resolveStatuses()

    DependencyUpdatesTask.executionDataCache[storageKey] =
      DependencyUpdatesTask.ExecutionData(
        projectStatuses = projectStatuses,
        buildscriptStatuses = buildscriptStatuses,
        outputFormatterArgument = task.outputFormatterArgument,
      )
    task.clearConfigurationTimeState()
  }
}
