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
package com.github.benmanes.gradle.versions.updates

import groovy.transform.TupleConstructor
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency

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
@TupleConstructor
class DependencyUpdates {
  static final String COMPARATOR_17 =
    'org.gradle.api.internal.artifacts.version.LatestVersionSemanticComparator'
  static final String COMPARATOR_18 =
    'org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.ResolverStrategy'

  def project
  def revision

  /** Evaluates the dependencies and returns a reporter. */
  def run() {
    def current = getProjectAndBuildscriptDependencies()
    def (resolved, unresolved) = resolveLatestDepedencies(current)
    def (currentVersions, latestVersions, upToDateVersions, downgradeVersions, upgradeVersions) =
      composeVersionMapping(current, resolved)
    new DependencyUpdatesReporter(project, revision, currentVersions, latestVersions,
      upToDateVersions, downgradeVersions, upgradeVersions, unresolved)
  }

  /** Returns {@link ExternalDependency} collected from all projects and buildscripts. */
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
    def latest = current.collect { dependency ->
      project.dependencies.create(group: dependency.group, name: dependency.name,
          version: "latest.${revision}") {
        transitive = false
      }
    }
    resolveWithAllRepositories {
      def conf = project.configurations.detachedConfiguration(latest as Dependency[])
        .resolvedConfiguration.lenientConfiguration
      [conf.getFirstLevelModuleDependencies(SATISFIES_ALL), conf.unresolvedModuleDependencies]
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
  private def composeVersionMapping(current, resolved) {
    def currentVersions = current.collectEntries { dependency ->
       [keyOf(dependency), dependency.version]
    }
    def latestVersions = resolved.collectEntries { dependency ->
      [keyOf(dependency.module.id), dependency.moduleVersion]
    }
    def comparator = getVersionComparator()

    def versionInfo = latestVersions.groupBy { key, version ->
      if (currentVersions[key] == version){
        return 0
      }
      return (Math.signum(comparator.compare(version, currentVersions[key]))) as int
    }
    def upToDateVersions = versionInfo[0] ?: []
    def upgradeVersions = versionInfo[1] ?: []
    def downgradeVersions = versionInfo[-1] ?: []
    [currentVersions, latestVersions, upToDateVersions, downgradeVersions, upgradeVersions]
  }

  /** Retrieves the internal version comparator compatible with the Gradle version. */
  def getVersionComparator() {
    def classLoader = Thread.currentThread().getContextClassLoader()
    if (project.gradle.gradleVersion < '1.8') {
      classLoader.loadClass(COMPARATOR_17).newInstance()
    } else {
      classLoader.loadClass(COMPARATOR_18).newInstance().getVersionMatcher()
    }
  }

  /** Returns a key based on the dependency's group and name. */
  def static keyOf(dependency) { [group: dependency.group, name: dependency.name] }
}
