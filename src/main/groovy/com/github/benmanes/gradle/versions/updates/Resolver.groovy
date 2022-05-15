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

import static org.codehaus.groovy.runtime.DefaultGroovyMethods.asBoolean
import static org.codehaus.groovy.runtime.DefaultGroovyMethods.getMetaClass
import static org.gradle.api.specs.Specs.SATISFIES_ALL

import com.github.benmanes.gradle.versions.updates.BaseResolver.Companion.ProjectUrl
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.transform.CompileStatic
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import javax.annotation.Nullable
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.LenientConfiguration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.UnresolvedDependency

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
    if (asBoolean(getMetaClass(copy).respondsTo(copy, "setCanBeResolved", Boolean))
    ) {
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
  Project getProject() {
    return project
  }

  @Nullable
  @Override
  Action<? super ResolutionStrategyWithCurrent> getResolutionStrategy() {
    return resolutionStrategy
  }

  @Override
  boolean getCheckConstraints() {
    return checkConstraints
  }

  @Override
  ConcurrentMap<ModuleVersionIdentifier, ProjectUrl> getProjectUrls() {
    return projectUrls
  }
}
