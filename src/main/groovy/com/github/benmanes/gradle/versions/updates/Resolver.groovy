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
import groovyx.gpars.GParsPool
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.Project
import org.gradle.api.artifacts.ComponentMetadata
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.LenientConfiguration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolutionStrategy
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.UnresolvedDependency
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.result.ArtifactResolutionResult
import org.gradle.api.artifacts.result.ArtifactResult
import org.gradle.api.artifacts.result.ComponentArtifactsResult
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact

import static groovy.transform.TypeCheckingMode.SKIP
import static org.gradle.api.specs.Specs.SATISFIES_ALL

/**
 * Resolves the configuration to determine the version status of its dependencies.
 */
@TypeChecked
class Resolver {
  final Object pool
  final Project project
  final Closure resolutionStrategy
  final boolean useSelectionRules
  final boolean collectProjectUrls
  final ConcurrentMap<ModuleVersionIdentifier, ProjectUrl> projectUrls

  Resolver(Project project, Closure resolutionStrategy, Object pool) {
    this.projectUrls = new ConcurrentHashMap<>()
    this.resolutionStrategy = resolutionStrategy
    this.project = project
    this.pool = pool

    useSelectionRules = new VersionComparator(project)
      .compare(project.gradle.gradleVersion, '2.2') >= 0
    collectProjectUrls = new VersionComparator(project)
      .compare(project.gradle.gradleVersion, '2.0') >= 0

    logRepositories()
  }

  /** Returns the version status of the configuration's dependencies at the given revision. */
  public Set<DependencyStatus> resolve(Configuration configuration, String revision) {
    Map<Coordinate.Key, Coordinate> coordinates = getCurrentCoordinates(configuration)
    Configuration latestConfiguration = createLatestConfiguration(configuration, revision)

    LenientConfiguration lenient = latestConfiguration.resolvedConfiguration.lenientConfiguration
    Set<ResolvedDependency> resolved = lenient.getFirstLevelModuleDependencies(SATISFIES_ALL)
    Set<UnresolvedDependency> unresolved = lenient.getUnresolvedModuleDependencies()
    return getStatus(coordinates, resolved, unresolved)
  }

  /** Returns the version status of the configuration's dependencies. */
  @TypeChecked(SKIP)
  private Set<DependencyStatus> getStatus(Map<Coordinate.Key, Coordinate> coordinates,
    Set<ResolvedDependency> resolved, Set<UnresolvedDependency> unresolved) {
    Set<DependencyStatus> result = Collections.synchronizedSet(new HashSet<>())

    GParsPool.withExistingPool(pool) {
      resolved.eachParallel { dependency ->
        Coordinate resolvedCoordinate = Coordinate.from(dependency.module.id)
        Coordinate originalCoordinate = coordinates.get(resolvedCoordinate.key)
        Coordinate coord = originalCoordinate ?: resolvedCoordinate
        if ((originalCoordinate == null) && (resolvedCoordinate.groupId != 'null')) {
          project.logger.info("Skipping hidden dependency: ${resolvedCoordinate}")
        } else {
          String projectUrl = getProjectUrl(dependency.module.id)
          result.add(new DependencyStatus(coord, resolvedCoordinate.version, projectUrl))
        }
      }
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
      createQueryDependency(dependency, revision)
    }

    Configuration copy = configuration.copyRecursive().setTransitive(false)
    // https://github.com/ben-manes/gradle-versions-plugin/issues/127
    if (copy.metaClass.respondsTo(copy, "setCanBeResolved", Boolean)) {
      copy.setCanBeResolved(true)
    }

    copy.dependencies.clear()
    copy.dependencies.addAll(latest)

    if (useSelectionRules) {
      addRevisionFilter(copy, revision)
      addCustomResolutionStrategy(copy)
    }
    return copy
  }

  /** Returns a variant of the provided dependency used for querying the latest version. */
  @TypeChecked(SKIP)
  private Dependency createQueryDependency(Dependency dependency, String revision) {
    String versionQuery = useSelectionRules ? '+' : "latest.${revision}"

    // If no version was specified then it may be intended to be resolved by another plugin
    // (e.g. the dependency-management-plugin for BOMs) or is an explicit file (e.g. libs/*.jar).
    // In the case of another plugin we use '+' in the hope that the plugin will not restrict the
    // query (see issue #97). Otherwise if its a file then use 'none' to pass it through.
    String version = (dependency.version == null) ? (dependency.artifacts.empty ? '+' : 'none') : versionQuery

    return project.dependencies.create("${dependency.group}:${dependency.name}:${version}") {
      transitive = false
    }
  }

  /** Adds a revision filter by rejecting candidates using a component selection rule. */
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

  /** Adds a custom resolution strategy only applicable for the dependency updates task. */
  private void addCustomResolutionStrategy(Configuration configuration) {
    if (resolutionStrategy != null) {
      configuration.resolutionStrategy(resolutionStrategy)
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

    // https://github.com/ben-manes/gradle-versions-plugin/issues/231
    boolean transitive = declared.values().any { it.version == 'none' }

    Map<Coordinate.Key, Coordinate> coordinates = [:]
    Configuration copy = configuration.copyRecursive().setTransitive(transitive)
    // https://github.com/ben-manes/gradle-versions-plugin/issues/127
    if (copy.metaClass.respondsTo(copy, "setCanBeResolved", Boolean)) {
      copy.setCanBeResolved(true)
    }
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

  private void logRepositories() {
    boolean root = (project.rootProject == project)
    String label = "${root ? project.name : project.path} project${root ? ' (root)' : ''}"
    if (!project.buildscript.configurations*.dependencies.isEmpty()) {
      project.logger.info("Resolving ${label} buildscript with repositories:")
      for (ArtifactRepository repository : project.buildscript.repositories) {
        logRepository(repository)
      }
    }
    project.logger.info("Resolving ${label} configurations with repositories:")
    for (ArtifactRepository repository : project.repositories) {
      logRepository(repository)
    }
  }

  @TypeChecked(SKIP)
  private void logRepository(ArtifactRepository repository) {
    if (repository instanceof FlatDirectoryArtifactRepository) {
      project.logger.info(" - ${repository.name}: ${repository.dirs}")
    } else if (repository instanceof MavenArtifactRepository ||
      repository instanceof IvyArtifactRepository) {
      project.logger.info(" - ${repository.name}: ${repository.url}")
    } else {
      project.logger.info(" - ${repository.name}: ${repository.getClass().simpleName}")
    }
  }

  private String getProjectUrl(ModuleVersionIdentifier id) {
    if (!collectProjectUrls || project.getGradle().startParameter.isOffline()) {
      return null
    }

    ProjectUrl projectUrl = new ProjectUrl()
    ProjectUrl cached = projectUrls.putIfAbsent(id, projectUrl)
    if (cached != null) {
      projectUrl = cached
    }
    synchronized (projectUrl) {
      if (!projectUrl.isResolved) {
        projectUrl.isResolved = true
        projectUrl.url = resolveProjectUrl(id)
      }
      return projectUrl.url
    }
  }

  private String resolveProjectUrl(ModuleVersionIdentifier id) {
    try {
      ArtifactResolutionResult resolutionResult = project.dependencies.createArtifactResolutionQuery()
              .forComponents(DefaultModuleComponentIdentifier.newId(id))
              .withArtifacts(MavenModule, MavenPomArtifact)
              .execute()

      // size is 0 for gradle plugins, 1 for normal dependencies
      for (ComponentArtifactsResult result : resolutionResult.resolvedComponents) {
        Set<ArtifactResult> artifacts = result.getArtifacts(MavenPomArtifact)
        // size should always be 1
        for (ArtifactResult artifact : result.getArtifacts(MavenPomArtifact)) {
          if (artifact instanceof ResolvedArtifactResult) {
            File file = ((ResolvedArtifactResult) artifact).file
            project.logger.info("Pom file for ${id} is ${file}")

            String url = getUrlFromPom(file)
            if (url) {
              project.logger.info("Found url for ${id}: ${url}")
              return url
            } else {
              ModuleVersionIdentifier parent = getParentFromPom(file)
              if (parent && "${parent.group}:${parent.name}" != "org.sonatype.oss:oss-parent") {
                url = getProjectUrl(parent)
                if (url) {
                  return url
                }
              }
            }
          }
        }
      }
      project.logger.info("Did not find url for ${id}")
      return null
    } catch (Exception e) {
      project.logger.info("Failed to resolve the project's url", e)
      return null
    }
  }

  @TypeChecked(SKIP)
  private static String getUrlFromPom(File file) {
    def pom = new XmlSlurper(/* validating */ false, /* namespaceAware */ false).parse(file)
    if (pom.url) {
      return pom.url
    }
    return pom.scm.url
  }

  @TypeChecked(SKIP)
  private static ModuleVersionIdentifier getParentFromPom(File file) {
    def pom = new XmlSlurper(/* validating */ false, /* namespaceAware */ false).parse(file)
    def parent = pom.children().find { child -> child.name() == 'parent' }
    if (parent) {
      String groupId = parent.groupId
      String artifactId = parent.artifactId
      String version = parent.version
      if (groupId && artifactId && version) {
        return DefaultModuleVersionIdentifier.newId(groupId, artifactId, version)
      }
    }
    return null
  }

  private static final class ProjectUrl {
    boolean isResolved
    String url
  }
}
