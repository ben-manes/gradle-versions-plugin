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
import javax.annotation.Nullable
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
class DependencyUpdates extends BaseDependencyUpdates {
  Project project
  @Nullable
  Action<? super ResolutionStrategyWithCurrent> resolutionStrategy
  String revision
  @Nullable
  Object outputFormatter
  String outputDir
  @Nullable
  String reportfileName
  boolean checkForGradleUpdate
  String gradleReleaseChannel
  boolean checkConstraints
  boolean checkBuildEnvironmentConstraints

  /**
   * Evaluates the project dependencies and then the buildScript dependencies to apply different
   * task options and returns a reporter for the results.
   */
  DependencyUpdatesReporter run() {
    Map<Project, Set<Configuration>> projectConfigs = project.allprojects
      .collectEntries { proj -> [proj, new LinkedHashSet<>(proj.configurations)] }
    Set<DependencyStatus> status = resolveProjects(projectConfigs, checkConstraints,
      resolutionStrategy, revision)

    Map<Project, Set<Configuration>> buildscriptProjectConfigs = project.allprojects
      .collectEntries { proj -> [proj, new LinkedHashSet<>(proj.buildscript.configurations)] }
    Set<DependencyStatus> buildscriptStatus = resolveProjects(
      buildscriptProjectConfigs, checkBuildEnvironmentConstraints, resolutionStrategy, revision)

    Set<DependencyStatus> statuses = status + buildscriptStatus
    VersionMapping versions = new VersionMapping(project, statuses)
    Set<UnresolvedDependency> unresolved =
      statuses.findAll { it.unresolved != null }
        .collect { it.unresolved } as Set
    Map<Map<String, String>, String> projectUrls = statuses
      .findAll { it.projectUrl }
      .collectEntries {
        [[group: it.coordinate.groupId, name: it.coordinate.artifactId]: it.projectUrl]
      }
    return createReporter(versions, unresolved, projectUrls)
  }

  private Set<DependencyStatus> resolveProjects(
    Map<Project, Set<Configuration>> projectConfigs, boolean checkConstraints,
    Action<? super ResolutionStrategyWithCurrent> resolutionStrategy, String revision) {
    HashSet<DependencyStatus> resultStatus = new HashSet<>()
    projectConfigs.each { currentProject, currentConfigurations ->
      Resolver resolver = new Resolver(currentProject, resolutionStrategy, checkConstraints)
      for (Configuration currentConfiguration : currentConfigurations) {
        for (DependencyStatus newStatus: resolve(resolver, currentProject, currentConfiguration, revision)) {
          addValidatedDependencyStatus(resultStatus, newStatus)
        }
      }
    }
    return resultStatus
  }

  private DependencyUpdatesReporter createReporter(VersionMapping versions,
    Set<UnresolvedDependency> unresolved, Map<Map<String, String>, String> projectUrls) {
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

    return new DependencyUpdatesReporter(project, revision, outputFormatter, outputDir,
      reportfileName, currentVersions, latestVersions, upToDateVersions, downgradeVersions,
      upgradeVersions, versions.undeclared, unresolved, projectUrls, gradleUpdateChecker,
      gradleReleaseChannel)
  }
}
