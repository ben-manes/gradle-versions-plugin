/*
 * Copyright 2012-2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.gradle.versions.updates

import groovy.transform.TypeChecked
import org.gradle.api.Project
import org.gradle.api.artifacts.ComponentMetadata
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.LenientConfiguration
import org.gradle.api.artifacts.ResolutionStrategy
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.UnresolvedDependency
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.initialization.dsl.ScriptHandler

import static groovy.transform.TypeCheckingMode.SKIP
import static org.gradle.api.specs.Specs.SATISFIES_ALL

/**
 * Resolves the configuration to determine the version status of its dependencies.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
@TypeChecked
class Resolver {
  final Map<ScriptHandler, List<ArtifactRepository>> repositoriesForBuildscript;
  final Map<Project, List<ArtifactRepository>> repositoriesForProject;
  final Set<ArtifactRepository> allRepositories;
  final Project project

  Resolver(Project project) {
    this.project = project

    Set<ArtifactRepository> all = []
    repositoriesForBuildscript = project.allprojects.collectEntries { proj ->
      all.addAll(proj.buildscript.repositories)
      [proj, new HashSet(proj.buildscript.repositories)]
    }
    repositoriesForProject = project.allprojects.collectEntries { proj ->
      all.addAll(proj.repositories)
      [proj, new HashSet(proj.repositories)]
    }

    // Only RepositoryHandler knows how to determine equivalence
    project.repositories.clear()
    allRepositories = all.findAll { project.repositories.add(it) } as Set

    logRepositories()
  }

  /** Returns the version status of the configuration's dependencies at the given revision. */
  public Set<DependencyStatus> resolve(Configuration configuration, String revision) {
    Map<Coordinate.Key, Coordinate> coordinates = getCurrentCoordinates(configuration)
    Configuration latestConfiguration = createLatestConfiguration(configuration, revision)

    resolveWithAllRepositories {
      LenientConfiguration lenient = latestConfiguration.resolvedConfiguration.lenientConfiguration
      Set<ResolvedDependency> resolved = lenient.getFirstLevelModuleDependencies(SATISFIES_ALL)
      Set<UnresolvedDependency> unresolved = lenient.getUnresolvedModuleDependencies()
      return getStatus(coordinates, resolved, unresolved)
    }
  }

  /** Returns the version status of the configuration's dependencies. */
  private Set<DependencyStatus> getStatus(Map<Coordinate.Key, Coordinate> coordinates,
      Set<ResolvedDependency> resolved,Set<UnresolvedDependency> unresolved) {
    Set<DependencyStatus> result = []
    for (ResolvedDependency dependency : resolved) {
      Coordinate resolvedCoordinate = Coordinate.from(dependency.module.id)
      Coordinate originalCoordinate = coordinates.get(resolvedCoordinate.key)
      Coordinate coord = originalCoordinate ?: resolvedCoordinate
      result.add(new DependencyStatus(coord, resolvedCoordinate.version))
    }
    for (UnresolvedDependency dependency : unresolved) {
      Coordinate resolvedCoordinate = Coordinate.from(dependency.selector)
      Coordinate originalCoordinate = coordinates.get(resolvedCoordinate.key)
      Coordinate coord = originalCoordinate ?: resolvedCoordinate
      result.add(new DependencyStatus(coord, dependency))
    }
    return result
  }

  /** Returns a copy of the configuration where dependencies will be resolved up to the revision. */
  private Configuration createLatestConfiguration(Configuration configuration, String revision) {
    List<Dependency> latest = configuration.dependencies.findAll { dependency ->
      dependency instanceof ExternalDependency
    }.collect { dependency ->
      createQueryDependency(dependency)
    }

    Configuration copy = configuration.copyRecursive().setTransitive(false)
    copy.dependencies.clear()
    copy.dependencies.addAll(latest)
    addRevisionFilter(copy, revision)
    return copy
  }

  /** Returns a variant of the provided dependency used for querying the latest version. */
  @TypeChecked(SKIP)
  private Dependency createQueryDependency(Dependency dependency) {
    String version = (dependency.version == null) ? 'none' : '+'
    return project.dependencies.create("${dependency.group}:${dependency.name}:${version}") {
      transitive = false
    }
  }

  /** Add a revision filter by rejecting candidates using a component selection rule. */
  @TypeChecked(SKIP)
  private void addRevisionFilter(Configuration configuration, String revision) {
    configuration.resolutionStrategy { ResolutionStrategy componentSelection ->
      componentSelection.componentSelection { rules ->
        rules.all { ComponentSelection selection, ComponentMetadata metadata ->
          boolean accepted =
            ((revision == 'release') && (metadata.status == 'release')) ||
            ((revision == 'milestone') && (metadata.status != 'integration')) ||
            (revision == 'integration') || (selection.candidate.version == 'none')
          if (!accepted) {
            selection.reject("Component status ${metadata.status} rejected by revision ${revision}")
          }
        }
      }
    }
  }

  /** Returns the coordinates for the current (declared) dependency versions. */
  private Map<Coordinate.Key, Coordinate> getCurrentCoordinates(Configuration configuration) {
    Map<Coordinate.Key, Coordinate> declared = configuration.dependencies.findAll { dependency ->
      dependency instanceof ExternalDependency
    }.collectEntries {
      Coordinate coordinate = Coordinate.from(it)
      return [coordinate.key, coordinate]
    }
    if (declared.isEmpty()) {
      return Collections.emptyMap()
    }

    return resolveWithAllRepositories {
      Map<Coordinate.Key, Coordinate> coordinates = [:]
      Configuration copy = configuration.copyRecursive().setTransitive(false)
      LenientConfiguration lenient = copy.resolvedConfiguration.lenientConfiguration

      Set<ResolvedDependency> resolved = lenient.getFirstLevelModuleDependencies(SATISFIES_ALL)
      for (ResolvedDependency dependency : resolved) {
        Coordinate coordinate = Coordinate.from(dependency.module.id)
        coordinates.put(coordinate.key, coordinate)
      }

      Set<UnresolvedDependency> unresolved = lenient.getUnresolvedModuleDependencies()
      for (UnresolvedDependency dependency : unresolved) {
        Coordinate coordinate = Coordinate.from(dependency.selector)
        coordinates.put(coordinate.key, declared.get(coordinate.key))
      }

      // Ignore undeclared (hidden) dependencies that appear when resolving a configuration
      coordinates.keySet().retainAll(declared.keySet())

      return coordinates
    }
  }

  /**
   * Performs the closure with the project temporarily using all of the repositories collected
   * across all projects. The additional repositories added are removed after the operation
   * completes.
   */
  private <T> T resolveWithAllRepositories(Closure<T> closure) {
    project.allprojects.each { proj ->
      proj.buildscript.repositories.clear()
      proj.buildscript.repositories.addAll(allRepositories)

      proj.repositories.clear()
      proj.repositories.addAll(allRepositories)
    }
    try {
      closure.call()
    } finally {
      repositoriesForProject.each { proj, original ->
        proj.repositories.clear()
        proj.repositories.addAll(original)
      }
      repositoriesForBuildscript.each { buildscript, original ->
        buildscript.repositories.clear()
        buildscript.repositories.addAll(original)
      }
    }
  }

  @TypeChecked(SKIP)
  private void logRepositories() {
    project.logger.info('Resolving with repositories:')
    allRepositories.each {
      if (it instanceof FlatDirectoryArtifactRepository) {
        project.logger.info(" - $it.name: ${it.dirs}")
      } else if (it instanceof MavenArtifactRepository || it instanceof IvyArtifactRepository) {
        project.logger.info(" - $it.name: $it.url");
      } else {
        project.logger.info(" - $it.name: ${it.getClass().simpleName}")
      }
    }
  }
}
