/*
 * Copyright 2012-2014 Ben Manes. All Rights Reserved.
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

import groovy.transform.TupleConstructor
import groovy.transform.TypeChecked
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

import static groovy.transform.TypeCheckingMode.SKIP
import static org.gradle.api.specs.Specs.SATISFIES_ALL

/**
 * An evaluator for reporting of which dependencies have later versions.
 * <p>
 * The <tt>revision</tt> property controls the resolution strategy:
 * <ul>
 *   <li>release: selects the latest release
 *   <li>milestone: select the latest version being either a milestone or a release (default)
 *   <li>integration: selects the latest revision of the dependency module (such as SNAPSHOT)
 * </ul>
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
@TypeChecked
@TupleConstructor
class DependencyUpdates {
  Project project
  Closure resolutionStrategy
  String revision
  Object outputFormatter
  String outputDir

  /** Evaluates the dependencies and returns a reporter. */
  DependencyUpdatesReporter run() {
    Map<Project, Set<Configuration>> projectConfigs = project.allprojects.collectEntries { proj ->
      [proj, proj.configurations.plus(proj.buildscript.configurations) as Set]
    }
    Set<DependencyStatus> status = projectConfigs.collectMany { proj, configurations ->
      Resolver resolver = new Resolver(proj, resolutionStrategy)
      return configurations.collectMany { config ->
        try {
          return resolver.resolve(config, revision)
        } catch (Exception e) {
          String msg = "Failed to resolve ${proj.path}:${config.name}"
          project.logger.error(msg, project.logger.isInfoEnabled() ? e : null)
          return []
        }
      }
    } as Set

    VersionMapping versions = new VersionMapping(project, status)
    Set<UnresolvedDependency> unresolved =
      status.findAll { it.unresolved != null }.collect { it.unresolved } as Set
    return createReporter(versions, unresolved)
  }

  private DependencyUpdatesReporter createReporter(
      VersionMapping versions, Set<UnresolvedDependency> unresolved) {
    Map<Map<String, String>, String> currentVersions =
      versions.current.collectEntries { [[group: it.groupId, name: it.artifactId]: it.version] }
    Map<Map<String, String>, String> latestVersions =
      versions.latest.collectEntries { [[group: it.groupId, name: it.artifactId]: it.version] }
    Map<Map<String, String>, String> upToDateVersions =
      versions.upToDate.collectEntries { [[group: it.groupId, name: it.artifactId]: it.version] }
    Map<Map<String, String>, String> downgradeVersions = toMap(versions.downgrade)
    Map<Map<String, String>, String> upgradeVersions = toMap(versions.upgrade)

    return new DependencyUpdatesReporter(project, revision, outputFormatter, outputDir,
      currentVersions, latestVersions, upToDateVersions, downgradeVersions, upgradeVersions, unresolved)
  }

  private Map<Map<String, String>, String> toMap(Set<Coordinate> coordinates) {
    Map<Map<String, String>, String> map = [:]
    for (Coordinate coordinate : coordinates) {
      for (int i = 0; ; i++) {
        String artifactId = coordinate.artifactId + ((i == 0) ? '' : "[${i + 1}]")
        def key = [group: coordinate.groupId, name: artifactId]
        if (!map.containsKey(key)) {
          map.put(key, coordinate.version)
          break
        }
      }
    }
    return map
  }
}
