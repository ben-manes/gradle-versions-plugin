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
  String revision
  Object outputFormatter
  String outputDir
  Boolean useProjectAsFilename

  /** Evaluates the dependencies and returns a reporter. */
  DependencyUpdatesReporter run() {
    def current = getProjectAndBuildscriptDependencies()
    project.logger.info('Found dependencies {}', current)
    List<Set> dependencies = resolveLatestDependencies(current)
    Set<ResolvedDependency> resolvedLatest = dependencies[0]
    Set<UnresolvedDependency> unresolved = dependencies[1]
    project.logger.info('Resolved latest dependencies: {}', resolvedLatest)
    project.logger.info('Unresolved dependencies: {}', unresolved)

    Map<Map<String, String>, String> currentVersions = [:]
    current.each { Dependency dependency ->

      if (unresolved.find { UnresolvedDependency it -> keyOf(it.selector) == keyOf(dependency) }) {
        project.logger.info('Could not determine current version for dependency: {}', dependency)
        //add current version info based on info from dependency
        currentVersions.put(keyOf(dependency), dependency.version ?: "none")
      } else {
        def actualVersion = resolveActualDependencyVersion(dependency)
        currentVersions.put(keyOf(dependency), actualVersion ?: "none")
      }
    }
    List<Map<Map<String, String>, String>> versions = composeVersionMapping(currentVersions, resolvedLatest)
    Map<Map<String, String>, String> latestVersions = versions[0]
    Map<Map<String, String>, String> upToDateVersions = versions[1]
    Map<Map<String, String>, String> downgradeVersions = versions[2]
    Map<Map<String, String>, String> upgradeVersions = versions[3]
    new DependencyUpdatesReporter(project, revision, outputFormatter, outputDir, useProjectAsFilename, currentVersions,
      latestVersions, upToDateVersions, downgradeVersions, upgradeVersions, unresolved)
  }

  /** Returns {@link ExternalDependency} collected from all projects and buildscripts. */
  @TypeChecked(SKIP)
  private Collection<Dependency> getProjectAndBuildscriptDependencies() {
    return project.allprojects
        // get all dependency configuration for the build and the build script
        .collectMany { it.configurations + it.buildscript.configurations }
        // flatten out the dependencies from each config
        .collectMany { it.allDependencies}.unique()
        // exclude any submodule deps
        .findAll { it instanceof ExternalDependency } as Collection<Dependency>
  }

  /**
   * Returns {@link Set<ResolvedDependency>} and {@link Set<UnresolvedDependency>} collected after evaluating
   * the latest dependencies to determine the newest versions.
   */
  private List<Set> resolveLatestDependencies(Collection<Dependency> current) {
    // try to resolve each of the dependencies
    Collection<Dependency> latest = current.collect { Dependency dependency ->
      def newDep = [name:     dependency.name,
                    group:    dependency.group,
                    version:  dependency.version ? "latest.${revision}" : "none"]
      project.dependencies.create(newDep) { ModuleDependency dep -> dep.transitive = false }
    }
    resolveWithAllRepositories {
      def conf = project.configurations.detachedConfiguration(latest as Dependency[]).resolvedConfiguration.lenientConfiguration
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

    // save the original repos so we can restore them later
    def orig = project.repositories.asImmutable()

    // add all the repos for the top level project and all sub projects
    project.repositories.addAll(project.allprojects.collectMany { Project proj -> (proj.repositories + proj.buildscript.repositories) })

    // remove duplicates
    project.repositories.unique(true)

    // log the repos we are using
    project.repositories.each {
      if(it instanceof FlatDirectoryArtifactRepository) {
        project.logger.info " - $it.name: ${it.dirs}"
      } else if(it instanceof MavenArtifactRepository || it instanceof IvyArtifactRepository){
        project.logger.info " - $it.name: $it.url";
      } else {
        project.logger.info " - $it.name: ${it.getClass().simpleName}"
      }
    }

    try {
      // do the work
      closure.call()
    } finally {
      // restore original set of repos
      project.repositories.retainAll(orig)
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
      [keyOf(dependency.module.id), dependency.moduleVersion ?: "none"]
    }

    project.logger.info('Comparing current with latest dependencies. current: {}, latest: {}',
            currentVersions.collect { key, value -> "${key.group}:${key.name}:$value" },
            latestVersions.collect { key, value -> "${key.group}:${key.name}:$value" })

    Comparator comparator = getVersionComparator()

    Map<Integer, Map<Map<String, String>, String>> versionInfo = latestVersions.groupBy {
        Map<String, String> key, version ->
      project.logger.info('Checking dependency {}:{}', key.group, key.name)

      def currentVersion = currentVersions[key]
      if (currentVersion == version) {
        0
      } else {
        (Math.signum(comparator.compare(version, currentVersion))) as int
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

    Comparator comparator = comparatorCreatorCandidates.findResult {
      try{
        (Comparator)it.call()
      } catch (Exception ignored){
        null
      }
    }
    assert comparator != null, "Could not create a version comparator for Gradle version ${project.gradle.gradleVersion}"
    comparator
  }
 
  /** Returns a key based on the dependency's group and name. */
  @TypeChecked(SKIP)
  static Map<String,String> keyOf(identifier) { [group: identifier.group ?: "none", name: identifier.name] }
}
