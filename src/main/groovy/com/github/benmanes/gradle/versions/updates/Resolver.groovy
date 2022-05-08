/*
 * Copyright 2012-2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.gradle.versions.updates

import static groovy.transform.TypeCheckingMode.SKIP
import static org.gradle.api.specs.Specs.SATISFIES_ALL

import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.util.slurpersupport.GPathResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import javax.annotation.Nullable
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ComponentMetadata
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyConstraint
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
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.HasConfigurableAttributes
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact

/**
 * Resolves the configuration to determine the version status of its dependencies.
 */
@CompileStatic
class Resolver {
  private final Project project
  @Nullable
  private final Action<? super ResolutionStrategyWithCurrent> resolutionStrategy
  private final boolean checkConstraints
  private final ConcurrentMap<ModuleVersionIdentifier, ProjectUrl> projectUrls

  Resolver(Project project,
    @Nullable Action<? super ResolutionStrategyWithCurrent> resolutionStrategy,
    boolean checkConstraints) {
    this.project = project
    this.resolutionStrategy = resolutionStrategy
    this.checkConstraints = checkConstraints
    this.projectUrls = new ConcurrentHashMap<>()

    logRepositories()
  }

  /** Returns the version status of the configuration's dependencies at the given revision. */
  Set<DependencyStatus> resolve(Configuration configuration, String revision) {
    Map<Coordinate.Key, Coordinate> coordinates = getCurrentCoordinates(configuration)
    Configuration latestConfiguration = createLatestConfiguration(configuration, revision,
      coordinates)

    LenientConfiguration lenient = latestConfiguration.resolvedConfiguration.lenientConfiguration
    Set<ResolvedDependency> resolved = lenient.getFirstLevelModuleDependencies(SATISFIES_ALL)
    Set<UnresolvedDependency> unresolved = lenient.getUnresolvedModuleDependencies()
    return getStatus(coordinates, resolved, unresolved)
  }

  /** Returns the version status of the configuration's dependencies. */
  private Set<DependencyStatus> getStatus(Map<Coordinate.Key, Coordinate> coordinates,
    Set<ResolvedDependency> resolved, Set<UnresolvedDependency> unresolved) {
    Set<DependencyStatus> result = new HashSet<>()

    for (dependency in resolved) {
      Coordinate resolvedCoordinate = Coordinate.from(dependency.module.id)
      Coordinate originalCoordinate = coordinates.get(resolvedCoordinate.key)
      Coordinate coord = originalCoordinate ?: resolvedCoordinate
      if ((originalCoordinate == null) && (resolvedCoordinate.groupId != "null")) {
        project.logger.info("Skipping hidden dependency: ${resolvedCoordinate}")
      } else {
        String projectUrl = getProjectUrl(dependency.module.id)
        result.add(new DependencyStatus(coord, resolvedCoordinate.version, projectUrl))
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
  private Configuration createLatestConfiguration(Configuration configuration, String revision,
    Map<Coordinate.Key, Coordinate> currentCoordinates) {
    List<Dependency> latest = configuration.dependencies.findAll { dependency ->
      dependency instanceof ExternalDependency
    }.collect { dependency ->
      createQueryDependency(dependency)
    }

    // Common use case for dependency constraints is a java-platform BOM project or to control
    // version of transitive dependency.
    if (supportsConstraints(configuration)) {
      for (dependency in configuration.dependencyConstraints) {
        latest.add(createQueryDependency(dependency))
      }
    }

    Configuration copy = configuration.copyRecursive().setTransitive(false)
    // https://github.com/ben-manes/gradle-versions-plugin/issues/127
    if (copy.metaClass.respondsTo(copy, "setCanBeResolved", Boolean)) {
      copy.setCanBeResolved(true)
    }

    // https://github.com/ben-manes/gradle-versions-plugin/issues/592
    // allow resolution of dynamic latest versions regardless of the original strategy
    if (copy.resolutionStrategy.metaClass.hasProperty(copy.resolutionStrategy,
      "failOnDynamicVersions")) {
      copy.resolutionStrategy.metaClass.setProperty(copy.resolutionStrategy,
        "failOnDynamicVersions", false)
    }

    // Resolve using the latest version of explicitly declared dependencies and retains Kotlin's
    // inherited stdlib dependencies from the super configurations. This is required for variant
    // resolution, but the full set can break consumer capability matching.
    Set<Dependency> inherited = configuration.allDependencies.findAll { dependency ->
      (dependency instanceof ExternalDependency) &&
        (dependency.group == "org.jetbrains.kotlin") &&
        (dependency.version != null)
    } - configuration.dependencies

    // Adds the Kotlin 1.2.x legacy metadata to assist in variant selection
    Configuration metadata = project.configurations.findByName("commonMainMetadataElements")
    if (metadata == null) {
      Configuration compile = project.configurations.findByName("compile")
      if (compile != null) {
        addAttributes(copy, compile, { String key -> key.contains("kotlin") })
      }
    } else {
      addAttributes(copy, metadata)
    }

    copy.dependencies.clear()
    copy.dependencies.addAll(latest)
    copy.dependencies.addAll(inherited)

    addRevisionFilter(copy, revision)
    addAttributes(copy, configuration)
    addCustomResolutionStrategy(copy, currentCoordinates)
    return copy
  }

  /** Returns a variant of the provided dependency used for querying the latest version. */
  @TypeChecked(SKIP)
  private Dependency createQueryDependency(Dependency dependency) {
    // If no version was specified then it may be intended to be resolved by another plugin
    // (e.g. the dependency-management-plugin for BOMs) or is an explicit file (e.g. libs/*.jar).
    // In the case of another plugin we use "+" in the hope that the plugin will not restrict the
    // query (see issue #97). Otherwise if its a file then use "none" to pass it through.
    String version = (dependency.version == null)
      ? (dependency.artifacts.empty ? "+" : "none")
      : "+"

    // Format the query with an optional classifier and extension
    String query = "${dependency.group}:${dependency.name}:${version}"
    if (!dependency.artifacts.isEmpty()) {
      if (dependency.artifacts[0].classifier) {
        query += ":${dependency.artifacts[0].classifier}"
      }
      if (dependency.artifacts[0].extension) {
        query += "@${dependency.artifacts[0].extension}"
      }
    }

    Dependency latest = project.dependencies.create(query) {
      transitive = false
    }

    // Copy selection qualifiers if the artifact was not explicitly set
    if (dependency.artifacts.isEmpty()) {
      addAttributes(latest, dependency)
    }

    return latest
  }

  /** Returns a variant of the provided dependency used for querying the latest version. */
  @TypeChecked(SKIP)
  private Dependency createQueryDependency(DependencyConstraint dependency) {
    // If no version was specified then use "none" to pass it through.
    String version = dependency.version == null ? "none" : "+"

    return project.dependencies.create("${dependency.group}:${dependency.name}:${version}") {
      transitive = false
    }
  }

  /** Adds the attributes from the source to the target. */
  @TypeChecked(SKIP)
  private static void addAttributes(HasConfigurableAttributes target,
    HasConfigurableAttributes source, Closure<?> filter = { String key -> true }) {
    target.attributes { container ->
      for (Attribute<?> key : source.attributes.keySet()) {
        if (filter.call(key.name)) {
          Object value = source.attributes.getAttribute(key)
          container.attribute(key, value)
        }
      }
    }
  }

  /** Adds a revision filter by rejecting candidates using a component selection rule. */
  @TypeChecked(SKIP)
  private static void addRevisionFilter(Configuration configuration, String revision) {
    configuration.resolutionStrategy { ResolutionStrategy componentSelection ->
      componentSelection.componentSelection { rules ->
        Closure<?> revisionFilter = { ComponentSelection selection, ComponentMetadata metadata ->
          boolean accepted = (metadata == null) ||
            ((revision == "release") && (metadata.status == "release")) ||
            ((revision == "milestone") && (metadata.status != "integration")) ||
            (revision == "integration") || (selection.candidate.version == "none")
          if (!accepted) {
            selection.reject("Component status ${metadata.status} rejected by revision ${revision}")
          }
        }
        rules.all {
          ComponentSelection.methods.any { it.name == "getMetadata" }
            ? { revisionFilter(it, it.metadata) }
            : revisionFilter
        }
      }
    }
  }

  /** Adds a custom resolution strategy only applicable for the dependency updates task. */
  private void addCustomResolutionStrategy(Configuration configuration,
    Map<Coordinate.Key, Coordinate> currentCoordinates) {
    if (resolutionStrategy != null) {
      configuration.resolutionStrategy(new Action<ResolutionStrategy>() {
        @Override
        void execute(ResolutionStrategy inner) {
          resolutionStrategy.execute(new ResolutionStrategyWithCurrent(inner as ResolutionStrategy,
            currentCoordinates))
        }
      })
    }
  }

  /** Returns the coordinates for the current (declared) dependency versions. */
  private Map<Coordinate.Key, Coordinate> getCurrentCoordinates(Configuration configuration) {
    Map<Coordinate.Key, Coordinate> declared =
      getResolvableDependencies(configuration).collectEntries {
        return [it.key, it]
      }

    if (declared.isEmpty()) {
      return Collections.emptyMap()
    }

    // https://github.com/ben-manes/gradle-versions-plugin/issues/231
    boolean transitive = declared.values().any { it.version == "none" }

    Map<Coordinate.Key, Coordinate> coordinates = new HashMap<>()
    Configuration copy = configuration.copyRecursive().setTransitive(transitive)
    // https://github.com/ben-manes/gradle-versions-plugin/issues/127
    if (copy.metaClass.respondsTo(copy, "setCanBeResolved", Boolean)) {
      copy.setCanBeResolved(true)
    }
    LenientConfiguration lenient = copy.resolvedConfiguration.lenientConfiguration

    Set<ResolvedDependency> resolved = lenient.getFirstLevelModuleDependencies(SATISFIES_ALL)
    for (ResolvedDependency dependency : resolved) {
      Coordinate coordinate = Coordinate.from(dependency.module.id, declared)
      coordinates.put(coordinate.key, coordinate)
    }

    Set<UnresolvedDependency> unresolved = lenient.getUnresolvedModuleDependencies()
    for (UnresolvedDependency dependency : unresolved) {
      Coordinate.Key key = Coordinate.keyFrom(dependency.selector)
      coordinates.put(key, declared.get(key))
    }

    if (supportsConstraints(copy)) {
      for (DependencyConstraint constraint : copy.dependencyConstraints) {
        Coordinate coordinate = Coordinate.from(constraint)
        // Only add a constraint to the report if there is no dependency matching it, this means it
        // is targeting a transitive dependency or is part of a platform.
        if (!coordinates.containsKey(coordinate.key)) {
          coordinates.put(coordinate.key, declared.get(coordinate.key))
        }
      }
    }

    // Ignore undeclared (hidden) dependencies that appear when resolving a configuration
    coordinates.keySet().retainAll(declared.keySet())

    return coordinates
  }

  private void logRepositories() {
    boolean root = (project.rootProject == project)
    String label = "${root ? project.name : project.path} project${root ? " (root)" : ""}"
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

  @Nullable
  private String getProjectUrl(ModuleVersionIdentifier id) {
    if (project.getGradle().startParameter.isOffline()) {
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

  @Nullable
  private String resolveProjectUrl(ModuleVersionIdentifier id) {
    try {
      ArtifactResolutionResult resolutionResult = project
        .dependencies.createArtifactResolutionQuery()
        .forComponents(DefaultModuleComponentIdentifier.newId(id))
        .withArtifacts(MavenModule, MavenPomArtifact)
        .execute()

      // size is 0 for gradle plugins, 1 for normal dependencies
      for (ComponentArtifactsResult result : resolutionResult.resolvedComponents) {
        // size should always be 1
        for (ArtifactResult artifact : result.getArtifacts(MavenPomArtifact)) {
          if (artifact instanceof ResolvedArtifactResult) {
            File file = ((ResolvedArtifactResult) artifact).file
            project.logger.info("Pom file for ${id} is ${file}")

            String url = getUrlFromPom(file)
            if (url) {
              project.logger.info("Found url for ${id}: ${url}")
              return url.trim()
            } else {
              ModuleVersionIdentifier parent = getParentFromPom(file)
              if (parent && "${parent.group}:${parent.name}" != "org.sonatype.oss:oss-parent") {
                url = getProjectUrl(parent)
                if (url) {
                  return url.trim()
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

  @TypeChecked(SKIP) // GPathResult
  private static String getUrlFromPom(File file) {
    GPathResult pom = new XmlSlurper(/* validating */ false, /* namespaceAware */ false).parse(file)
    Object url = pom.url
    if (url != null) {
      return url
    }
    return pom.scm.url
  }

  @TypeChecked(SKIP) // GPathResult
  @Nullable
  private static ModuleVersionIdentifier getParentFromPom(File file) {
    GPathResult pom = new XmlSlurper(/* validating */ false, /* namespaceAware */ false).parse(file)
    GPathResult parent = pom.children().find { child -> child.name() == "parent" }
    if (parent != null) {
      String groupId = parent.groupId
      String artifactId = parent.artifactId
      String version = parent.version
      if (groupId != null && artifactId != null && version != null) {
        return DefaultModuleVersionIdentifier.newId(groupId, artifactId, version)
      }
    }
    return null
  }

  private boolean supportsConstraints(Configuration configuration) {
    return checkConstraints &&
      configuration.metaClass.respondsTo(configuration, "getDependencyConstraints")
  }

  private List<Coordinate> getResolvableDependencies(Configuration configuration) {
    List<Coordinate> coordinates = configuration.dependencies.findAll { dependency ->
      dependency instanceof ExternalDependency
    }.collect { dependency ->
      Coordinate.from(dependency)
    }

    if (supportsConstraints(configuration)) {
      configuration.dependencyConstraints.each {
        coordinates.add(Coordinate.from(it))
      }
    }

    return coordinates
  }

  private static final class ProjectUrl {
    boolean isResolved
    String url
  }
}
