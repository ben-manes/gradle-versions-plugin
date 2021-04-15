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

import com.github.benmanes.gradle.versions.reporter.JsonReporter
import com.github.benmanes.gradle.versions.reporter.PlainTextReporter
import com.github.benmanes.gradle.versions.reporter.Reporter
import com.github.benmanes.gradle.versions.reporter.XmlReporter
import com.github.benmanes.gradle.versions.reporter.HtmlReporter
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
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor
import groovy.transform.TypeCheckingMode
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleVersionSelector
import org.gradle.api.artifacts.UnresolvedDependency

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.NIGHTLY
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE

/**
 * A reporter for the dependency updates results.
 */
@CompileStatic
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
  Map<Map<String, String>, Coordinate> currentVersions
  /** The latest versions of each dependency (as scoped by the revision level). */
  Map<Map<String, String>, Coordinate> latestVersions

  /** The dependencies that are up to date (same as latest found). */
  Map<Map<String, String>, Coordinate> upToDateVersions
  /** The dependencies that exceed the latest found (e.g. may not want SNAPSHOTs). */
  Map<Map<String, String>, Coordinate> downgradeVersions
  /** The dependencies where upgrades were found (below latest found). */
  Map<Map<String, String>, Coordinate> upgradeVersions
  /** The dependencies that could not be resolved. */
  Set<UnresolvedDependency> unresolved

  /** Project urls of maven dependencies  */
  Map<Map<String, String>, String> projectUrls

  /** facade object to access information about running gradle versions and gradle updates */
  GradleUpdateChecker gradleUpdateChecker

  /** The gradle release channel to use for reporting. */
  String gradleReleaseChannel

  private static final Object MUTEX = new Object()

  def write() {
    synchronized (MUTEX) {
      if (!(outputFormatter instanceof Closure)) {
        PlainTextReporter plainTextReporter = new PlainTextReporter(project, revision, gradleReleaseChannel)
        plainTextReporter.write(System.out, buildBaseObject())
      }

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
    File filename = new File(outputDir, reportfileName + '.' + reporter.getFileExtension())
    project.file(outputDir).mkdirs()
    File outputFile = project.file(filename)
    outputFile.withPrintWriter { PrintWriter pw ->
      def result = buildBaseObject()
      reporter.write(pw, result)
    }

    project.logger.lifecycle '\nGenerated report file ' + filename
  }

  Reporter getOutputReporter(String formatterOriginal) {
    String formatter =  formatterOriginal.replaceAll("\\s", "")
    def reporter

    switch (formatter) {
      case 'json':
        reporter = new JsonReporter(project, revision, gradleReleaseChannel)
        break
      case 'xml':
        reporter = new XmlReporter(project, revision, gradleReleaseChannel)
        break
      case 'html':
        reporter = new HtmlReporter(project, revision, gradleReleaseChannel)
        break
      default:
        reporter = new PlainTextReporter(project, revision, gradleReleaseChannel)
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
    boolean enabled = gradleUpdateChecker.isEnabled()
    return new GradleUpdateResults(
      enabled: enabled,
      running: new GradleUpdateResult(enabled, gradleUpdateChecker.runningGradleVersion, gradleUpdateChecker.runningGradleVersion),
      current: new GradleUpdateResult(enabled, gradleUpdateChecker.runningGradleVersion, gradleUpdateChecker.currentGradleVersion),
      releaseCandidate: new GradleUpdateResult(enabled && (gradleReleaseChannel == RELEASE_CANDIDATE.id || gradleReleaseChannel == NIGHTLY.id), gradleUpdateChecker.runningGradleVersion, gradleUpdateChecker.releaseCandidateGradleVersion),
      nightly: new GradleUpdateResult(enabled && (gradleReleaseChannel == NIGHTLY.id), gradleUpdateChecker.runningGradleVersion, gradleUpdateChecker.nightlyGradleVersion)
    )
  }

  private static void updateKey(Map<String, String> existingKey) {
    int index = existingKey['name'].lastIndexOf('[')
    existingKey['name'] = (index == -1) ? existingKey['name'] : existingKey['name'].substring(0, index)
  }

  protected SortedSet buildCurrentGroup() {
    sortByGroupAndName(upToDateVersions).collect { Map.Entry<Map<String, String>, Coordinate> dep ->
      updateKey(dep.key)
      buildDependency(dep.value, dep.key)
    } as SortedSet
  }

  protected SortedSet buildOutdatedGroup() {
    sortByGroupAndName(upgradeVersions).collect { Map.Entry<Map<String, String>, Coordinate> dep ->
      updateKey(dep.key)
      buildOutdatedDependency(dep.value, dep.key)
    } as SortedSet
  }

  protected SortedSet buildExceededGroup() {
    sortByGroupAndName(downgradeVersions).collect { Map.Entry<Map<String, String>, Coordinate> dep ->
      updateKey(dep.key)
      buildExceededDependency(dep.value, dep.key)
    } as SortedSet
  }

  protected SortedSet<DependencyUnresolved> buildUnresolvedGroup() {
    unresolved.sort { UnresolvedDependency a, UnresolvedDependency b ->
      compareKeys(keyOf(a.selector), keyOf(b.selector))
    }.collect { UnresolvedDependency dep ->
      def stringWriter = new StringWriter()
      dep.problem.printStackTrace(new PrintWriter(stringWriter))
      def message = stringWriter.toString()

      buildUnresolvedDependency(dep.selector, message)
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

  protected def buildDependency(Coordinate coordinate, Map<String, String> key) {
    new Dependency(key['group'], key['name'], coordinate.getVersion(), projectUrls[key],
      coordinate.getUserReason())
  }

  protected def buildExceededDependency(Coordinate coordinate, Map<String, String> key) {
    new DependencyLatest(key['group'], key['name'], coordinate?.getVersion(), projectUrls[key],
      coordinate?.getUserReason(), latestVersions[key]?.getVersion())
  }

  protected def buildUnresolvedDependency(ModuleVersionSelector selector, String message) {
    new DependencyUnresolved(selector.group, selector.name,
      currentVersions[keyOf(selector)]?.getVersion(),
      latestVersions[keyOf(selector)]?.getVersion(),
      currentVersions[keyOf(selector)]?.getUserReason(),
      message)
  }

  protected def buildOutdatedDependency(Coordinate coordinate, Map<String, String> key) {
    def available

    String laterVersion = latestVersions[key]?.getVersion();
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

    new DependencyOutdated(key['group'], key['name'], coordinate?.getVersion(),
      projectUrls[key], coordinate?.getUserReason(), available)
  }

  def sortByGroupAndName(Map<Map<String, String>, Coordinate> dependencies) {
    dependencies.sort { Map.Entry<Map<String, String>, Coordinate> a,
      Map.Entry<Map<String, String>, Coordinate> b -> compareKeys(a.key, b.key)
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
