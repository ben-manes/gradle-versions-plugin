/*
 * Copyright 2012 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.gradle.versions

import groovy.transform.TupleConstructor
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.internal.artifacts.version.LatestVersionSemanticComparator

/**
 * An evaluator for reporting of which dependencies have newer versions.
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
@TupleConstructor
class DependencyUpdates {
  def project
  def revision

  /** Evaluates the dependencies and returns a reporter. */
  def run() {
    def current = getProjectAndBuildscriptDependencies()
    def (resolved, unresolved) = resolveLatestDepedencies(current)
    def (currentVersions, latestVersions, upToDateVersions, downgradeVersions, upgradeVersions) =
      getVersionMapping(current, resolved)
    new DependencyUpdatesReporter(project, revision, currentVersions, latestVersions,
      upToDateVersions, downgradeVersions, upgradeVersions, unresolved)
  }

  /** Returns {@link ExternalDependency} collected from the project and buildscript. */
  private def getProjectAndBuildscriptDependencies() {
    project.allprojects.collectMany{ proj ->
      def configurations = (proj.configurations + proj.buildscript.configurations)
      configurations.collectMany { it.allDependencies }
    }.findAll { it instanceof ExternalDependency }
  }

  /**
   * Returns {@link ResolvedDependency} and {@link UnresolvedDependency} collected after evaluating
   * the latest dependencies to determine the newest versions.
   */
  private def resolveLatestDepedencies(current) {
    if (current.empty) {
      return [[], []]
    }
    def unresolved = current.collect { dependency ->
      project.dependencies.create(group: dependency.group, name: dependency.name,
          version: "latest.${revision}") {
        transitive = false
      }
    }
    resolveWithAllRepositories {
      def lenient = project.configurations.detachedConfiguration(unresolved as Dependency[])
        .resolvedConfiguration.lenientConfiguration
      [lenient.firstLevelModuleDependencies, lenient.unresolvedModuleDependencies]
    }
  }

  /**
   * Performs the closure with the project temporarily using all of the repositories collected
   * across all projects. The additional repositories added are removed after the operation
   * completes.
   */
  private def resolveWithAllRepositories(closure) {
    def repositories = project.allprojects.collectMany{ proj ->
      (proj.repositories + proj.buildscript.repositories)
    }.findAll { project.repositories.add(it) }

    project.logger.info 'Resolving with repositories:'
    project.repositories.each { repository ->
      def hasUrl = repository.metaClass.respondsTo(repository, 'url')
      project.logger.info ' - ' + repository.name + (hasUrl ? ": ${repository.url}" : '')
    }

    try {
      closure.call()
    } finally {
      project.repositories.removeAll(repositories)
    }
  }

  /** Organizes the dependencies into version mappings. */
  private def getVersionMapping(current, resolved) {
    def currentVersions = current.collectEntries { dependency ->
       [keyOf(dependency), dependency.version]
    }
    def latestVersions = resolved.collectEntries { dependency ->
      [keyOf(dependency.module.id), dependency.moduleVersion]
    }
    def upToDateVersions = currentVersions.intersect(latestVersions)

    def comparator = new LatestVersionSemanticComparator()
    def upgradeVersions = latestVersions.findAll { key, version ->
      comparator.compare(version, currentVersions[key]) > 0
    }
    def downgradeVersions = latestVersions.findAll { key, version ->
      comparator.compare(version, currentVersions[key]) < 0
    }
    [currentVersions, latestVersions, upToDateVersions, downgradeVersions, upgradeVersions]
  }

  /** Returns a key based on the dependency's group and name. */
  def static keyOf(dependency) { [group: dependency.group, name: dependency.name] }
}
