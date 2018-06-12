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

import com.github.benmanes.gradle.versions.reporter.JsonReporter
import com.github.benmanes.gradle.versions.reporter.PlainTextReporter
import com.github.benmanes.gradle.versions.reporter.Reporter
import com.github.benmanes.gradle.versions.reporter.XmlReporter
import com.github.benmanes.gradle.versions.reporter.result.DependenciesGroup
import com.github.benmanes.gradle.versions.reporter.result.Dependency
import com.github.benmanes.gradle.versions.reporter.result.DependencyLatest
import com.github.benmanes.gradle.versions.reporter.result.DependencyOutdated
import com.github.benmanes.gradle.versions.reporter.result.DependencyUnresolved
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.reporter.result.VersionAvailable
import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateChecker
import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResult
import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResults
import groovy.transform.TupleConstructor
import groovy.transform.TypeChecked
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleVersionSelector
import org.gradle.api.artifacts.UnresolvedDependency

/**
 * A reporter for the dependency updates results.
 */
@TypeChecked
@TupleConstructor
class DependencyUpdatesReporter {
  /** The project evaluated against. */
  Project project
  /** The revision strategy evaluated with. */
  String revision
  /** The output formatter strategy evaluated with. */
  Object outputFormatter
  /** The outputDir for report. */
  String outputDir
  /** The filename of the report file. */
  String reportfileName

  /** The current versions of each dependency declared in the project(s). */
  Map<Map<String, String>, String> currentVersions
  /** The latest versions of each dependency (as scoped by the revision level). */
  Map<Map<String, String>, String> latestVersions

  /** The dependencies that are up to date (same as latest found). */
  Map<Map<String, String>, String> upToDateVersions
  /** The dependencies that exceed the latest found (e.g. may not want SNAPSHOTs). */
  Map<Map<String, String>, String> downgradeVersions
  /** The dependencies where upgrades were found (below latest found). */
  Map<Map<String, String>, String> upgradeVersions
  /** The dependencies that could not be resolved. */
  Set<UnresolvedDependency> unresolved

  /** Project urls of maven dependencies  */
  Map<Map<String, String>, String> projectUrls

  /** facade object to access information about running gradle versions and gradle updates */
  GradleUpdateChecker gradleUpdateChecker

  private static final Object MUTEX = new Object()

  def write() {
    synchronized (MUTEX) {
      PlainTextReporter plainTextReporter = new PlainTextReporter(project, revision)

      plainTextReporter.write(System.out, buildBaseObject())

      if (outputFormatter == null ||
        (outputFormatter instanceof String && ((String) outputFormatter).isEmpty())) {
        project.logger.lifecycle('Skip generating report to file (outputFormatter is empty)')
        return
      }
      if (outputFormatter instanceof String) {
        ((String) outputFormatter).split(',').each {
          generateFileReport(getOutputReporter(it))
        }
      } else if (outputFormatter instanceof Reporter) {
        generateFileReport((Reporter) outputFormatter)
      } else if (outputFormatter instanceof Closure) {
        Result result = buildBaseObject()
        ((Closure) outputFormatter).call(result)
      } else {
        throw new IllegalArgumentException(
          "Cannot handle output formatter $outputFormatter, unsupported type")
      }
    }
  }

  def generateFileReport(Reporter reporter) {
    String filename = outputDir + File.separator + reportfileName + '.' + reporter.getFileExtension()
    try {
      project.file(outputDir).mkdirs()
      File outputFile = project.file(filename)
      outputFile.newOutputStream().withStream { OutputStream os ->
        new FileOutputStream(outputFile).withStream { FileOutputStream fos ->
          new PrintStream(fos).withStream { PrintStream ps ->
            def result = buildBaseObject()
            reporter.write(ps, result)
          }
        }
      }

      project.logger.lifecycle '\nGenerated report file ' + filename
    }
    catch (FileNotFoundException e) {
      project.logger.error 'Invalid outputDir path ' + filename
    }
  }

  def Reporter getOutputReporter(def formatter) {
    def reporter

    switch (formatter) {
      case 'json':
        reporter = new JsonReporter(project, revision)
        break
      case 'xml':
        reporter = new XmlReporter(project, revision)
        break
      default:
        reporter = new PlainTextReporter(project, revision)
    }

    return reporter
  }

  Result buildBaseObject() {
    SortedSet current = buildCurrentGroup()
    SortedSet outdated = buildOutdatedGroup()
    SortedSet exceeded = buildExceededGroup()
    SortedSet unresolved = buildUnresolvedGroup()

    def count = current.size() + outdated.size() + exceeded.size() + unresolved.size()

    buildObject(
      count,
      buildDependenciesGroup(current),
      buildDependenciesGroup(outdated),
      buildDependenciesGroup(exceeded),
      buildDependenciesGroup(unresolved),
      buildGradleUpdateResults()
    )
  }

  /**
   * Create a {@link GradleUpdateResults} object from the information provided by the {@link GradleUpdateChecker}
   * @return filled out object instance
   */
  private GradleUpdateResults buildGradleUpdateResults() {
    return new GradleUpdateResults(
      running: new GradleUpdateResult(gradleUpdateChecker.runningGradleVersion, gradleUpdateChecker.runningGradleVersion),
      current: new GradleUpdateResult(gradleUpdateChecker.runningGradleVersion, gradleUpdateChecker.currentGradleVersion),
      releaseCandidate: new GradleUpdateResult(gradleUpdateChecker.runningGradleVersion, gradleUpdateChecker.releaseCandidateGradleVersion),
      nightly: new GradleUpdateResult(gradleUpdateChecker.runningGradleVersion, gradleUpdateChecker.nightlyGradleVersion)
    )
  }

  protected SortedSet buildCurrentGroup() {
    sortByGroupAndName(upToDateVersions).collect { Map.Entry<Map<String, String>, String> dep ->
      buildDependency(dep.key['group'], dep.key['name'], dep.value, projectUrls[dep.key])
    } as SortedSet
  }

  protected SortedSet buildOutdatedGroup() {
    sortByGroupAndName(upgradeVersions).collect { Map.Entry<Map<String, String>, String> dep ->
      int index = dep.key['name'].lastIndexOf('[')
      dep.key['name'] = (index == -1) ? dep.key['name'] : dep.key['name'].substring(0, index)
      buildOutdatedDependency(
        dep.key['group'], dep.key['name'], dep.value, latestVersions[dep.key], projectUrls[dep.key])
    } as SortedSet
  }

  protected SortedSet buildExceededGroup() {
    sortByGroupAndName(downgradeVersions).collect { Map.Entry<Map<String, String>, String> dep ->
      int index = dep.key['name'].lastIndexOf('[')
      dep.key['name'] = (index == -1) ? dep.key['name'] : dep.key['name'].substring(0, index)
      buildExceededDependency(
        dep.key['group'], dep.key['name'], dep.value, latestVersions[dep.key], projectUrls[dep.key])
    } as SortedSet
  }

  protected SortedSet<DependencyUnresolved> buildUnresolvedGroup() {
    unresolved.sort { UnresolvedDependency a, UnresolvedDependency b ->
      compareKeys(keyOf(a.selector), keyOf(b.selector))
    }.collect { UnresolvedDependency dep ->
      def message = dep.problem.getMessage()
      def split = message.split('Required by')

      if (split.length > 0) {
        message = split[0].trim()
      }
      buildUnresolvedDependency(dep.selector.group, dep.selector.name,
        currentVersions[keyOf(dep.selector)], message, latestVersions[keyOf(dep.selector)])
    } as SortedSet
  }

  protected Result buildObject(int count,
                               DependenciesGroup current,
                               DependenciesGroup outdated,
                               DependenciesGroup exceeded,
                               DependenciesGroup unresolved,
                               GradleUpdateResults gradleUpdateResults) {
    new Result(count, current, outdated, exceeded, unresolved, gradleUpdateResults)
  }

  protected <T extends Dependency> DependenciesGroup<T> buildDependenciesGroup(
    SortedSet<T> dependencies) {
    new DependenciesGroup<T>(dependencies.size(), dependencies)
  }

  protected def buildDependency(String group, String name, String version, String projectUrl) {
    new Dependency(group, name, version, projectUrl)
  }

  protected def buildExceededDependency(String group, String name, String version,
    String latestVersion, String projectUrl) {
    new DependencyLatest(group, name, version, projectUrl, latestVersion)
  }

  protected def buildUnresolvedDependency(String group, String name, String version,
    String reason, String projectUrl) {
    new DependencyUnresolved(group, name, version, projectUrl, reason)
  }

  protected def buildOutdatedDependency(String group, String name, String version,
    String laterVersion, String projectUrl) {
    def available

    switch (revision) {
      case 'milestone':
        available = new VersionAvailable(null, laterVersion)
        break
      case 'integration':
        available = new VersionAvailable(null, null, laterVersion)
        break
      default:
        available = new VersionAvailable(laterVersion)
    }

    new DependencyOutdated(group, name, version, projectUrl, available)
  }

  def sortByGroupAndName(Map<Map<String, String>, String> dependencies) {
    dependencies.sort { Map.Entry<Map<String, String>, String> a,
      Map.Entry<Map<String, String>, String> b -> compareKeys(a.key, b.key)
    }
  }

  /** Compares the dependency keys. */
  protected static def compareKeys(Map<String, String> a, Map<String, String> b) {
    (a['group'] == b['group']) ? a['name'] <=> b['name'] : a['group'] <=> b['group']
  }

  static Map<String, String> keyOf(ModuleVersionSelector dependency) {
    [group: dependency.group, name: dependency.name]
  }
}
