package com.github.benmanes.gradle.versions.updates

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler

/**
 * Captures everything [Resolver] needs from a [Project] at configuration time, so that
 * no [Project] instance is dereferenced at execution time (avoiding CC instrumentation warnings).
 */
class ProjectContext(
  val name: String,
  val path: String,
  val isRootProject: Boolean,
  val isOffline: Boolean,
  val dependencyHandler: DependencyHandler,
  val configurationContainer: ConfigurationContainer,
  val repositories: RepositoryHandler,
  val buildscriptRepositories: RepositoryHandler,
  val buildscriptHasDependencies: Boolean,
  val label: String,
) {
  companion object {
    @JvmStatic
    fun from(project: Project): ProjectContext {
      val isRoot = project.rootProject == project
      return ProjectContext(
        name = project.name,
        path = project.path,
        isRootProject = isRoot,
        isOffline = project.gradle.startParameter.isOffline,
        dependencyHandler = project.dependencies,
        configurationContainer = project.configurations,
        repositories = project.repositories,
        buildscriptRepositories = project.buildscript.repositories,
        buildscriptHasDependencies =
          project.buildscript.configurations
            .flatMap { config -> config.dependencies }
            .any(),
        label = if (isRoot) "${project.name} project  (root)" else "${project.path} project ",
      )
    }
  }
}

/**
 * Pairs a [ProjectContext] with the set of [Configuration]s to resolve for that project.
 */
class ProjectConfigurations(
  val context: ProjectContext,
  val configurations: Set<Configuration>,
)
