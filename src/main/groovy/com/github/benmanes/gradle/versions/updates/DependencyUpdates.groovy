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

import static groovy.transform.TypeCheckingMode.SKIP
import static org.gradle.api.specs.Specs.SATISFIES_ALL
import groovy.transform.TupleConstructor
import groovy.transform.TypeChecked

import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository

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
  String revision
  Object outputFormatter
  String outputDir

  /** Evaluates the dependencies and returns a reporter. */
  DependencyUpdatesReporter run() {
    def current = getProjectAndBuildscriptDependencies()
    project.logger.info('Found dependencies {}', current)
    List<Set> dependencies = resolveLatestDepedencies(current)
    Set<ResolvedDependency> resolvedLatest = dependencies[0]
    Set<UnresolvedDependency> unresolved = dependencies[1]
    project.logger.info('Resolved latest dependencies: {}', resolvedLatest)
    project.logger.info('Unresolved dependencies: {}', unresolved)

    Map<Map<String, String>, String> currentVersions = [:]
    current.each { ExternalDependency dependency ->
      if (unresolved.find { UnresolvedDependency it -> keyOf(it.selector) == keyOf(dependency) }) {
        project.logger.info('Could not determine current version for dependency: {}', dependency)
        //add current version info based on info from dependency
        currentVersions.put(keyOf(dependency), dependency.version)
        return null
      }
      def actualVersion = resolveActualDependencyVersion(dependency)
      currentVersions.put(keyOf(dependency), actualVersion)

    }
    List<Map<Map<String, String>, String>> versions = composeVersionMapping(currentVersions, resolvedLatest)
    Map<Map<String, String>, String> latestVersions = versions[0]
    Map<Map<String, String>, String> upToDateVersions = versions[1]
    Map<Map<String, String>, String> downgradeVersions = versions[2]
    Map<Map<String, String>, String> upgradeVersions = versions[3]
    new DependencyUpdatesReporter(project, revision, outputFormatter, outputDir, currentVersions, latestVersions,
      upToDateVersions, downgradeVersions, upgradeVersions, unresolved)
  }

  /** Returns {@link ExternalDependency} collected from all projects and buildscripts. */
  private Collection<ExternalDependency> getProjectAndBuildscriptDependencies() {
    project.allprojects.collectMany { Project proj ->
      Collection<Configuration> configurations = proj.configurations + proj.buildscript.configurations
      configurations.collectMany { (Collection<Dependency>) it.allDependencies }
    }.findAll { it instanceof ExternalDependency }
  }

  /**
   * Returns {@link Set<ResolvedDependency>} and {@link Set<UnresolvedDependency>} collected after evaluating
   * the latest dependencies to determine the newest versions.
   */
  private List<Set> resolveLatestDepedencies(Collection<ExternalDependency> current) {
    Collection<Dependency> latest = current.collect { ExternalDependency dependency ->
      project.dependencies.create(group: dependency.group, name: dependency.name,
          version: (dependency.version == null ? null : "latest.${revision}")) { ModuleDependency dep ->
        dep.transitive = false
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
  @TypeChecked(SKIP)
  private <T> T resolveWithAllRepositories(Closure<T> closure) {
    project.logger.info 'Resolving with repositories:'

    def repositories = project.allprojects.collectMany { Project proj ->
      (proj.repositories + proj.buildscript.repositories)
    }.findAll { repository ->
      boolean flatDir = (repository instanceof FlatDirectoryArtifactRepository)
      if (flatDir) {
        project.logger.info(" - {}: {} (ignored)", repository.name, repository.dirs)
      }
      !flatDir
    }.findAll { project.repositories.add(it) }

    project.repositories.each { ArtifactRepository repository ->
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
   * Returns the version that is used for the given dependency.
   * Resolves dynamic versions (e.g. '1.+') to actual version numbers
   */
  private String resolveActualDependencyVersion(Dependency dependency) {
    def version = dependency.version
    boolean mightBeDynamicVersion = ((version != null)
            && (version.endsWith('+') || version.endsWith(']')
            || version.endsWith(')') || version.startsWith('latest.')))
    if (!mightBeDynamicVersion) {
      project.logger.info('Dependency {} does not use a dynamic version', dependency)
      return version
    }

    def actualVersion = resolveWithAllRepositories {
      project.configurations.detachedConfiguration(dependency).resolvedConfiguration.lenientConfiguration
      .getFirstLevelModuleDependencies(SATISFIES_ALL).find()?.moduleVersion ?: version
    }
    project.logger.info('Resolved actual version of dependency {} to {}', dependency, actualVersion)
    return actualVersion
  }

  /** Organizes the dependencies into version mappings. */
  private List<Map<Map<String, String>, String>> composeVersionMapping(
          Map<Map<String, String>, String> currentVersions,
          Set<ResolvedDependency> resolvedLatest) {

    Map<Map<String, String>, String> latestVersions = resolvedLatest.collectEntries { ResolvedDependency dependency ->
      [keyOf(dependency.module.id), dependency.moduleVersion]
    }

    project.logger.info('Comparing current with latest dependencies. current: {}, latest: {}',
            currentVersions.collect { key, value -> "${key.group}:${key.name}:$value" },
            latestVersions.collect { key, value -> "${key.group}:${key.name}:$value" })

    Comparator comparator = getVersionComparator()

    Map<Integer, Map<Map<String, String>, String>> versionInfo = latestVersions.groupBy {
        Map<String, String> key, version ->
      project.logger.info('Checking dependency {}:{}', key.group, key.name)
      if (currentVersions[key] == version) {
        0
      } else {
        (Math.signum(comparator.compare(version, currentVersions[key]))) as int
      }
    }
    Map<Map<String, String>, String> upToDateVersions = versionInfo[0] ?: [:] as Map<Map<String, String>, String>
    Map<Map<String, String>, String> upgradeVersions = versionInfo[1] ?: [:] as Map<Map<String, String>, String>
    Map<Map<String, String>, String> downgradeVersions = versionInfo[-1] ?: [:] as Map<Map<String, String>, String>
    [latestVersions, upToDateVersions, downgradeVersions, upgradeVersions]
  }

  /** Retrieves the internal version comparator compatible with the Gradle version. */
  @TypeChecked(SKIP)
  Comparator getVersionComparator() {
    def classLoader = Thread.currentThread().getContextClassLoader()

    def createInstance = { String className->
      classLoader.loadClass(className).newInstance()
    }

    def comparatorCreatorCandidates = [
      // 2.4
      {
        createInstance('org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator').asStringComparator()
      },
      // 2.3
      {
        createInstance('org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.StaticVersionComparator')
      },
      // 1.8
      {
        createInstance('org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.ResolverStrategy').getVersionMatcher()
      },
      // 1.0
      {
        createInstance('org.gradle.api.internal.artifacts.version.LatestVersionSemanticComparator')
      }
    ]

    def comparator = comparatorCreatorCandidates.findResult {
      try{
        it.call()
      } catch (Exception e){
        null
      }
    }
    assert comparator != null, "Could not create a version comparator for Gradle version ${project.gradle.gradleVersion}"
    comparator
  }
 
  /** Returns a key based on the dependency's group and name. */
  @TypeChecked(SKIP)
  static Map<String, String> keyOf(identifier) { [group: identifier.group, name: identifier.name] }
}
