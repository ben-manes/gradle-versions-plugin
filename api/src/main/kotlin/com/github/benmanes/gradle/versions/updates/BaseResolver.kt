package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.util.XmlSlurper
import groovy.util.slurpersupport.GPathResult
import groovy.util.slurpersupport.NodeChildren
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ComponentMetadata
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.UnresolvedDependency
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.specs.Specs.SATISFIES_ALL
import java.io.File

abstract class BaseResolver {

  abstract val project: Project

  abstract val resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?

  abstract fun supportsConstraints(configuration: Configuration): Boolean

  abstract fun getProjectUrl(id: ModuleVersionIdentifier): String?

  abstract fun resolveProjectUrl(id: ModuleVersionIdentifier): String?

  abstract fun getCurrentCoordinates(configuration: Configuration): Map<Coordinate.Key, Coordinate>

  abstract fun createQueryDependency(dependency: ModuleDependency): Dependency

  abstract fun createLatestConfiguration(
    configuration: Configuration,
    revision: String,
    currentCoordinates: Map<Coordinate.Key, Coordinate>,
  ): Configuration

  /** Returns the version status of the configuration's dependencies at the given revision. */
  fun resolve(configuration: Configuration, revision: String): Set<DependencyStatus> {
    val coordinates = getCurrentCoordinates(configuration)
    val latestConfiguration = createLatestConfiguration(configuration, revision, coordinates)
    val lenient = latestConfiguration.resolvedConfiguration.lenientConfiguration
    val resolved = lenient.getFirstLevelModuleDependencies(SATISFIES_ALL)
    val unresolved = lenient.unresolvedModuleDependencies
    return getStatus(coordinates, resolved, unresolved)
  }

  /** Returns the version status of the configuration's dependencies. */
  fun getStatus(
    coordinates: Map<Coordinate.Key, Coordinate>,
    resolved: Set<ResolvedDependency>,
    unresolved: Set<UnresolvedDependency>
  ): Set<DependencyStatus> {
    val result = hashSetOf<DependencyStatus>()
    for (dependency in resolved) {
      val resolvedCoordinate = Coordinate.from(dependency.module.id)
      val originalCoordinate = coordinates[resolvedCoordinate.key]
      val coord = originalCoordinate ?: resolvedCoordinate
      if (originalCoordinate == null && resolvedCoordinate.groupId != "null") {
        project.logger.info("Skipping hidden dependency: $resolvedCoordinate")
      } else {
        val projectUrl = getProjectUrl(dependency.module.id)
        result.add(DependencyStatus(coord, resolvedCoordinate.version, projectUrl))
      }
    }

    for (dependency in unresolved) {
      val resolvedCoordinate = Coordinate.from(dependency.selector)
      val originalCoordinate = coordinates[resolvedCoordinate.key]
      val coord = originalCoordinate ?: resolvedCoordinate
      result.add(DependencyStatus(coord, dependency))
    }
    return result
  }

  fun logRepositories() {
    val root = project.rootProject == project
    val label = "${
    if (root) {
      project.name
    } else {
      project.path
    }
    } project ${
    if (root) {
      " (root)"
    } else {
      ""
    }
    }"
    if (!project.buildscript.configurations
      .flatMap { config -> config.dependencies }
      .any()
    ) {
      project.logger.info("Resolving $label buildscript with repositories:")
      for (repository in project.buildscript.repositories) {
        logRepository(repository)
      }
    }
    project.logger.info("Resolving $label configurations with repositories:")
    for (repository in project.repositories) {
      logRepository(repository)
    }
  }

  fun logRepository(repository: ArtifactRepository) {
    when (repository) {
      is FlatDirectoryArtifactRepository -> {
        project.logger.info(" - ${repository.name}: ${repository.dirs}")
      }
      is IvyArtifactRepository -> {
        project.logger.info(" - ${repository.name}: ${repository.url}")
      }
      is MavenArtifactRepository -> {
        project.logger.info(" - ${repository.name}: ${repository.url}")
      }
      else -> {
        project.logger.info(" - ${repository.name}: ${repository.javaClass.simpleName}")
      }
    }
  }

  /** Returns a variant of the provided dependency used for querying the latest version.  */
  fun createQueryDependency(dependency: DependencyConstraint): Dependency {
    // If no version was specified then use "none" to pass it through.
    val version = if (dependency.version == null) "none" else "+"
    val nonTransitiveDependency =
      project.dependencies.create("${dependency.group}:${dependency.name}:$version") as ModuleDependency
    nonTransitiveDependency.isTransitive = false
    return nonTransitiveDependency
  }

  /** Adds a custom resolution strategy only applicable for the dependency updates task.  */
  fun addCustomResolutionStrategy(
    configuration: Configuration,
    currentCoordinates: Map<Coordinate.Key, Coordinate>
  ) {
    configuration.resolutionStrategy { inner ->
      resolutionStrategy?.execute(ResolutionStrategyWithCurrent(inner, currentCoordinates))
    }
  }

  /** Adds a revision filter by rejecting candidates using a component selection rule.  */
  fun addRevisionFilter(configuration: Configuration, revision: String) {
    configuration.resolutionStrategy { componentSelection ->
      componentSelection.componentSelection { rules ->
        val revisionFilter = { selection: ComponentSelection, metadata: ComponentMetadata? ->
          val accepted = (metadata == null) ||
            ((revision == "release") && (metadata.status == "release")) ||
            ((revision == "milestone") && (metadata.status != "integration")) ||
            (revision == "integration") || (selection.candidate.version == "none")
          if (!accepted) {
            selection.reject("Component status ${metadata?.status} rejected by revision $revision")
          }
        }
        rules.all { selectionAction ->
          if (ComponentSelection::class.members.any { it.name == "getMetadata" }) {
            revisionFilter(selectionAction, selectionAction.metadata)
          } else {
            revisionFilter
          }
        }
      }
    }
  }

  fun getResolvableDependencies(configuration: Configuration): List<Coordinate> {
    val coordinates = configuration.dependencies
      .filter { dependency -> dependency is ExternalDependency }
      .map { dependency ->
        Coordinate.from(dependency)
      } as MutableList<Coordinate>

    if (supportsConstraints(configuration)) {
      configuration.dependencyConstraints.forEach { dependencyConstraint ->
        coordinates.add(Coordinate.from(dependencyConstraint))
      }
    }
    return coordinates
  }

  companion object {
    @JvmStatic
    fun getUrlFromPom(file: File): String? {
      val pom = XmlSlurper(false, false).parse(file)
      val url = (pom.getProperty("url") as NodeChildren?)?.text()
      return url
        ?: ((pom.getProperty("scm") as NodeChildren?)?.getProperty("url") as NodeChildren?)?.text()
    }

    @JvmStatic
    fun getParentFromPom(file: File): ModuleVersionIdentifier? {
      val pom = XmlSlurper(false, false).parse(file)
      val parent: GPathResult? = pom.getProperty("parent") as NodeChildren?
      if (parent != null) {
        val groupId = (parent.getProperty("groupId") as NodeChildren?)?.text()
        val artifactId = (parent.getProperty("artifactId") as NodeChildren?)?.text()
        val version = (parent.getProperty("version") as NodeChildren?)?.text()
        if (groupId != null && artifactId != null && version != null) {
          return DefaultModuleVersionIdentifier.newId(groupId, artifactId, version)
        }
      }
      return null
    }

    class ProjectUrl {
      var resolved: Boolean = false
      var url: String? = null
    }
  }
}
