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
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import java.io.File

abstract class BaseResolver {

  abstract val project: Project

  abstract val resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?

  abstract fun supportsConstraints(configuration: Configuration): Boolean

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
