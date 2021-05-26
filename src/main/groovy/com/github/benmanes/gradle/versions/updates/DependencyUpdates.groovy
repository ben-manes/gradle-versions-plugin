/*
 * Copyright 2012-2014 Ben Manes. All Rights Reserved.
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

import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateChecker
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.UnresolvedDependency

/**
 * An evaluator for reporting of which dependencies have later versions.
 * <p>
 * The <tt>revision</tt> property controls the resolution strategy:
 * <ul>
 *   <li>release: selects the latest release
 *   <li>milestone: select the latest version being either a milestone or a release (default)
 *   <li>integration: selects the latest revision of the dependency module (such as SNAPSHOT)
 * </ul>
 */
@CompileStatic
@TupleConstructor
class DependencyUpdates {
  Project project
  Action<? super ResolutionStrategyWithCurrent> resolutionStrategy
  String revision
  Object outputFormatter
  String outputDir
  String reportfileName
  boolean checkForGradleUpdate
  String gradleReleaseChannel
  boolean checkConstraints

  /** Evaluates the dependencies and returns a reporter. */
  DependencyUpdatesReporter run() {
    Map<Project, Set<Configuration>> projectConfigs = project.allprojects.collectEntries { proj ->
      [proj, proj.configurations + (Set) proj.buildscript.configurations]
    }
    Set<DependencyStatus> status = resolveProjects(projectConfigs)

    VersionMapping versions = new VersionMapping(project, status)
    Set<UnresolvedDependency> unresolved =
      status.findAll { it.unresolved != null }.collect { it.unresolved } as Set
    Map<Map<String, String>, String> projectUrls = status.findAll { it.projectUrl }.collectEntries {
      [[group: it.coordinate.groupId, name: it.coordinate.artifactId]: it.projectUrl]
    }
    return createReporter(versions, unresolved, projectUrls)
  }

  private Set<DependencyStatus> resolveProjects(Map<Project, Set<Configuration>> projectConfigs) {
    Set<DependencyStatus> resultStatusSet = []
    for(Project currentProject : projectConfigs.keySet()) {
      Resolver resolver = new Resolver(currentProject, resolutionStrategy, checkConstraints)
      for(Configuration currentConfiguration : projectConfigs.get(currentProject)) {
        def isUsefulConfiguration = !currentConfiguration.canBeResolved || currentConfiguration.canBeConsumed || currentConfiguration.name == 'annotationProcessor' || currentConfiguration.name == 'kapt'
        if (isUsefulConfiguration) {
          for(DependencyStatus newStatus : resolve(resolver, currentProject, currentConfiguration)) {
            addValidatedDependencyStatus(resultStatusSet, newStatus)
          }
        }
      }
    }
    return resultStatusSet
  }

  /**
   * A new status will be added if
   * <ol>
   *   <li>{@link Coordinate.Key} of new status is not yet present in status collection <b>OR</b></li>
   *   <li>new status has concrete version (not {@code none}); the old status will then be removed if its coordinate is {@code none} versioned</li>
   * </ol>
   */
  private static void addValidatedDependencyStatus(Collection<DependencyStatus> statusCollection, DependencyStatus status) {
    DependencyStatus statusWithSameCoordinateKey = statusCollection.find { DependencyStatus it -> it.coordinate.key == status.coordinate.key}
    if( !statusWithSameCoordinateKey) {
      statusCollection.add(status)
    }
    else if(status.coordinate.version != 'none') {
      statusCollection.add(status)
      if(statusWithSameCoordinateKey.coordinate.version == 'none') {
        statusCollection.remove(statusWithSameCoordinateKey)
      }
    }
  }

  private Set<DependencyStatus> resolve(Resolver resolver, Project proj, Configuration config) {
    try {
      return resolver.resolve(config, revision)
    } catch (Exception e) {
      String msg = "Failed to resolve ${proj.path}:${config.name}"
      project.logger.error(msg, project.logger.isInfoEnabled() ? e : null)
      return Collections.emptySet()
    }
  }

  private DependencyUpdatesReporter createReporter(
    VersionMapping versions, Set<UnresolvedDependency> unresolved, Map<Map<String, String>, String> projectUrls) {
    Map<Map<String, String>, Coordinate> currentVersions =
      versions.current.collectEntries { [[group: it.groupId, name: it.artifactId]: it] }
    Map<Map<String, String>, Coordinate> latestVersions =
      versions.latest.collectEntries { [[group: it.groupId, name: it.artifactId]: it] }
    Map<Map<String, String>, Coordinate> upToDateVersions =
      versions.upToDate.collectEntries { [[group: it.groupId, name: it.artifactId]: it] }
    Map<Map<String, String>, Coordinate> downgradeVersions = toMap(versions.downgrade)
    Map<Map<String, String>, Coordinate> upgradeVersions = toMap(versions.upgrade)

    // Check for Gradle updates.
    GradleUpdateChecker gradleUpdateChecker = new GradleUpdateChecker(checkForGradleUpdate)

    return new DependencyUpdatesReporter(project, revision, outputFormatter, outputDir, reportfileName,
      currentVersions, latestVersions, upToDateVersions, downgradeVersions, upgradeVersions, versions.undeclared,
      unresolved, projectUrls, gradleUpdateChecker, gradleReleaseChannel)
  }

  private static Map<Map<String, String>, Coordinate> toMap(Set<Coordinate> coordinates) {
    Map<Map<String, String>, Coordinate> map = [:]
    for (Coordinate coordinate : coordinates) {
      for (int i = 0; ; i++) {
        String artifactId = coordinate.artifactId + ((i == 0) ? '' : "[${i + 1}]")
        def key = [group: coordinate.groupId, name: artifactId]
        if (!map.containsKey(key)) {
          map.put(key, coordinate)
          break
        }
      }
    }
    return map
  }
}
