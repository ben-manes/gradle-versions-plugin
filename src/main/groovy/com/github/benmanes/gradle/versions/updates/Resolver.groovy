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

import com.github.benmanes.gradle.versions.updates.BaseResolver.Companion.ProjectUrl
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import javax.annotation.Nullable
import kotlin.jvm.functions.Function1
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.LenientConfiguration
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.UnresolvedDependency
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.HasConfigurableAttributes

/**
 * Resolves the configuration to determine the version status of its dependencies.
 */
@CompileStatic
class Resolver extends BaseResolver {
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

  /** Returns a copy of the configuration where dependencies will be resolved up to the revision. */
  @Override
  Configuration createLatestConfiguration(Configuration configuration, String revision,
    Map<Coordinate.Key, Coordinate> currentCoordinates) {
    List<Dependency> latest = configuration.dependencies.findAll { dependency ->
      dependency instanceof ExternalDependency
    }.collect { dependency ->
      if (dependency instanceof ModuleDependency) {
        createQueryDependency(dependency)
      }
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

  /** Adds the attributes from the source to the target. */
  @TypeChecked(SKIP)
  @Override
  void addAttributes(HasConfigurableAttributes<?> target,
    HasConfigurableAttributes<?> source,
    Function1<? super String, Boolean> filter = { String key -> true }) {
    target.attributes { container ->
      for (Attribute<?> key : source.attributes.keySet()) {
        if (filter.invoke(key.name)) {
          Object value = source.attributes.getAttribute(key)
          container.attribute(key, value)
        }
      }
    }
  }

  /** Returns the coordinates for the current (declared) dependency versions. */
  @Override
  Map<Coordinate.Key, Coordinate> getCurrentCoordinates(Configuration configuration) {
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

  @Override
  boolean supportsConstraints(Configuration configuration) {
    return checkConstraints &&
      configuration.metaClass.respondsTo(configuration, "getDependencyConstraints")
  }

  @Override
  Project getProject() {
    return project
  }

  @Nullable
  @Override
  Action<? super ResolutionStrategyWithCurrent> getResolutionStrategy() {
    return resolutionStrategy
  }

  @Override
  ConcurrentMap<ModuleVersionIdentifier, ProjectUrl> getProjectUrls() {
    return projectUrls
  }
}
