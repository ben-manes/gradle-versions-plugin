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
  def outputFormatter
  def outputDir

  /** Evaluates the dependencies and returns a reporter. */
  def run() {
    def current = getProjectAndBuildscriptDependencies()
    project.logger.info('Found dependencies {}', current)
    def (resolvedLatest, unresolved) = resolveLatestDepedencies(current)
    project.logger.info('Resolved latest dependencies: {}', resolvedLatest)
    project.logger.info('Unresolved dependencies: {}', unresolved)

    def currentVersions = [:]
    current.each { dependency ->
      if (unresolved.find{keyOf(it.selector) == keyOf(dependency)}){
        project.logger.info('Could not determine current version for dependency: {}', dependency)
        //add current version info based on info from dependency
        currentVersions.put(keyOf(dependency), dependency['version'])
        return
      }else{
        def actualVersion = resolveActualDependencyVersion(dependency)
        currentVersions.put(keyOf(dependency), actualVersion)
      }
    }
    def (latestVersions, upToDateVersions, downgradeVersions, upgradeVersions) =
      composeVersionMapping(currentVersions, resolvedLatest)
    new DependencyUpdatesReporter(project, revision, outputFormatter, outputDir, currentVersions, latestVersions,
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
          version: (dependency.version == null ? null : "latest.${revision}")) {
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

  /**
   * Returns the version that is used for the given dependency. Resolves dynamic versions (e.g. '1.+') to actual version numbers
   */
  private def resolveActualDependencyVersion(Dependency dependency) {
    def version = dependency.version
    boolean mightBeDynamicVersion = version != null && (version.endsWith('+') || version.endsWith(']') || version.endsWith(')') || version.startsWith('latest.'))
    if (!mightBeDynamicVersion){
      project.logger.info("Dependency {} does not use a dynamic version", dependency)
      return version
    }
    
    def actualVersion = resolveWithAllRepositories{
      project.configurations.detachedConfiguration(dependency).resolvedConfiguration.lenientConfiguration
      .getFirstLevelModuleDependencies(SATISFIES_ALL).find()?.moduleVersion ?: version
    }
    project.logger.info("Resolved actual version of dependency {} to {}", dependency, actualVersion)
    return actualVersion
  }


  /** Organizes the dependencies into version mappings. */
  private def composeVersionMapping(currentVersions, resolvedLatest) {

    def latestVersions = resolvedLatest.collectEntries { dependency ->
      [keyOf(dependency.module.id), dependency.moduleVersion]
    }
    project.logger.info('Comparing current with latest dependencies. current: {}, latest: {}', currentVersions.collect{ key, value -> "${key.group}:${key.name}:$value" }, latestVersions.collect{ key, value -> "${key.group}:${key.name}:$value" })

    def comparator = getVersionComparator()

    def versionInfo = latestVersions.groupBy { key, version ->
      project.logger.info('Checking dependency {}:{}', key.group, key.name)
      if (currentVersions[key] == version) {
        0
      } else {
        (Math.signum(comparator.compare(version, currentVersions[key]))) as int
      }
    }
    def upToDateVersions = versionInfo[0] ?: []
    def upgradeVersions = versionInfo[1] ?: []
    def downgradeVersions = versionInfo[-1] ?: []
    [latestVersions, upToDateVersions, downgradeVersions, upgradeVersions]
  }

  /** Retrieves the internal version comparator compatible with the Gradle version. */
  def getVersionComparator() {
    def classLoader = Thread.currentThread().getContextClassLoader()

    if (project.gradle.gradleVersion =~ /^1\.[0-7](?:[^\d]|$)/) {
      classLoader.loadClass(COMPARATOR_17).newInstance()
    } else {
      classLoader.loadClass(COMPARATOR_18).newInstance().getVersionMatcher()
    }
  }

  /** Returns a key based on the dependency's group and name. */
  def static keyOf(dependency) { [group: dependency.group, name: dependency.name] }

}
