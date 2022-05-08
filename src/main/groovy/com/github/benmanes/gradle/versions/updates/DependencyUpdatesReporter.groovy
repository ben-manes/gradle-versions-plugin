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

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.NIGHTLY
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE

import com.github.benmanes.gradle.versions.reporter.AbstractReporter
import com.github.benmanes.gradle.versions.reporter.HtmlReporter
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
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor
import javax.annotation.Nullable
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleVersionSelector
import org.gradle.api.artifacts.UnresolvedDependency

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
  @Nullable
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
  /** The dependencies that were declared without version. */
  Set<Coordinate> undeclared
  /** The dependencies that could not be resolved. */
  Set<UnresolvedDependency> unresolved

  /** Project urls of maven dependencies  */
  Map<Map<String, String>, String> projectUrls

  /** facade object to access information about running gradle versions and gradle updates */
  GradleUpdateChecker gradleUpdateChecker

  /** The gradle release channel to use for reporting. */
  String gradleReleaseChannel

  private static final Object MUTEX = new Object()

  void write() {
    synchronized (MUTEX) {
      if (!(outputFormatter instanceof Closure<?>)) {
        PlainTextReporter plainTextReporter = new PlainTextReporter(project, revision,
          gradleReleaseChannel)
        plainTextReporter.write(System.out, buildBaseObject())
      }

      if (outputFormatter == null ||
        (outputFormatter instanceof String && ((String) outputFormatter).isEmpty())) {
        project.logger.lifecycle("Skip generating report to file (outputFormatter is empty)")
        return
      }
      if (outputFormatter instanceof String) {
        for (it in ((String) outputFormatter).split(",")) {
          generateFileReport(getOutputReporter(it))
        }
      } else if (outputFormatter instanceof Reporter) {
        generateFileReport((Reporter) outputFormatter)
      } else if (outputFormatter instanceof Closure<?>) {
        Result result = buildBaseObject()
        ((Closure<?>) outputFormatter).call(result)
      } else {
        throw new IllegalArgumentException(
          "Cannot handle output formatter $outputFormatter, unsupported type")
      }
    }
  }

  private void generateFileReport(Reporter reporter) {
    File filename = new File(outputDir, reportfileName + "." + reporter.getFileExtension())
    project.file(outputDir).mkdirs()
    File outputFile = project.file(filename)
    PrintStream stream = new PrintStream(outputFile)
    Result result = buildBaseObject()
    reporter.write(stream, result)
    stream.close()

    project.logger.lifecycle("\nGenerated report file " + filename)
  }

  private Reporter getOutputReporter(String formatterOriginal) {
    String formatter = formatterOriginal.replaceAll("\\s", "")
    AbstractReporter reporter

    switch (formatter) {
      case "json":
        reporter = new JsonReporter(project, revision, gradleReleaseChannel)
        break
      case "xml":
        reporter = new XmlReporter(project, revision, gradleReleaseChannel)
        break
      case "html":
        reporter = new HtmlReporter(project, revision, gradleReleaseChannel)
        break
      default:
        reporter = new PlainTextReporter(project, revision, gradleReleaseChannel)
    }

    return reporter
  }

  private Result buildBaseObject() {
    Set<Dependency> sortedCurrent = buildCurrentGroup()
    Set<DependencyOutdated> sortedOutdated = buildOutdatedGroup()
    Set<DependencyLatest> sortedExceeded = buildExceededGroup()
    Set<Dependency> SortedUndeclared = buildUndeclaredGroup()
    Set<DependencyUnresolved> sortedUnresolved = buildUnresolvedGroup()

    int count = sortedCurrent.size() + sortedOutdated.size() +
      sortedExceeded.size() +
      SortedUndeclared.size() +
      sortedUnresolved.size()

    return buildObject(
      count,
      buildDependenciesGroup(sortedCurrent),
      buildDependenciesGroup(sortedOutdated),
      buildDependenciesGroup(sortedExceeded),
      buildDependenciesGroup(SortedUndeclared),
      buildDependenciesGroup(sortedUnresolved),
      buildGradleUpdateResults()
    )
  }

  /**
   * Create a {@link GradleUpdateResults} object from the information provided by the {@link GradleUpdateChecker}
   * @return filled out object instance
   */
  private GradleUpdateResults buildGradleUpdateResults() {
    boolean enabled = gradleUpdateChecker.isEnabled()
    return new GradleUpdateResults(enabled,
      new GradleUpdateResult(enabled, gradleUpdateChecker.runningGradleVersion,
        gradleUpdateChecker.runningGradleVersion),
      new GradleUpdateResult(enabled, gradleUpdateChecker.runningGradleVersion,
        gradleUpdateChecker.currentGradleVersion),
      new GradleUpdateResult(
        enabled && (gradleReleaseChannel == RELEASE_CANDIDATE.id ||
          gradleReleaseChannel ==
          NIGHTLY.id), gradleUpdateChecker.runningGradleVersion,
        gradleUpdateChecker.releaseCandidateGradleVersion),
      new GradleUpdateResult(enabled && (gradleReleaseChannel == NIGHTLY.id),
        gradleUpdateChecker.runningGradleVersion, gradleUpdateChecker.nightlyGradleVersion)
    )
  }

  private static void updateKey(Map<String, String> existingKey) {
    int index = existingKey["name"].lastIndexOf("[")
    existingKey["name"] =
      (index == -1) ? existingKey["name"] : existingKey["name"].substring(0, index)
  }

  private Set<Dependency> buildCurrentGroup() {
    return sortByGroupAndName(upToDateVersions)
      .collect { Map.Entry<Map<String, String>, Coordinate> dep ->
        updateKey(dep.key)
        buildDependency(dep.value, dep.key)
      } as TreeSet<Dependency>
  }

  private Set<DependencyOutdated> buildOutdatedGroup() {
    return sortByGroupAndName(upgradeVersions)
      .collect { Map.Entry<Map<String, String>, Coordinate> dep ->
        updateKey(dep.key)
        buildOutdatedDependency(dep.value, dep.key)
      } as TreeSet<DependencyOutdated>
  }

  private Set<DependencyLatest> buildExceededGroup() {
    return sortByGroupAndName(downgradeVersions)
      .collect { Map.Entry<Map<String, String>, Coordinate> dep ->
        updateKey(dep.key)
        buildExceededDependency(dep.value, dep.key)
      } as TreeSet<DependencyLatest>
  }

  private Set<Dependency> buildUndeclaredGroup() {
    return undeclared
      .collect { Coordinate coordinate ->
        new Dependency(coordinate.groupId, coordinate.artifactId)
      } as TreeSet<Dependency>
  }

  private Set<DependencyUnresolved> buildUnresolvedGroup() {
    return unresolved.sort { UnresolvedDependency a, UnresolvedDependency b ->
      compareKeys(keyOf(a.selector), keyOf(b.selector))
    }.collect { UnresolvedDependency dep ->
      StringWriter stringWriter = new StringWriter()
      dep.problem.printStackTrace(new PrintWriter(stringWriter))
      String message = stringWriter.toString()

      buildUnresolvedDependency(dep.selector, message)
    } as TreeSet<DependencyUnresolved>
  }

  private static Result buildObject(int count,
    DependenciesGroup<Dependency> currentGroup,
    DependenciesGroup<DependencyOutdated> outdatedGroup,
    DependenciesGroup<DependencyLatest> exceededGroup,
    DependenciesGroup<Dependency> undeclaredGroup,
    DependenciesGroup<DependencyUnresolved> unresolvedGroup,
    GradleUpdateResults gradleUpdateResults) {
    return new Result(count, currentGroup, outdatedGroup, exceededGroup, undeclaredGroup,
      unresolvedGroup, gradleUpdateResults)
  }

  private static <T extends Dependency> DependenciesGroup<T> buildDependenciesGroup(
    Set<T> dependencies) {
    return new DependenciesGroup<T>(dependencies.size(), dependencies)
  }

  private Dependency buildDependency(Coordinate coordinate, Map<String, String> key) {
    return new Dependency(key["group"], key["name"], coordinate.getVersion(), projectUrls[key],
      coordinate.getUserReason())
  }

  private DependencyLatest buildExceededDependency(Coordinate coordinate,
    Map<String, String> key) {
    return new DependencyLatest(key["group"], key["name"], coordinate?.getVersion(),
      projectUrls[key],
      coordinate?.getUserReason(), latestVersions[key]?.getVersion())
  }

  private DependencyUnresolved buildUnresolvedDependency(ModuleVersionSelector selector,
    String message) {
    return new DependencyUnresolved(selector.group, selector.name,
      currentVersions[keyOf(selector)]?.getVersion(),
      latestVersions[keyOf(selector)]?.getVersion(),
      currentVersions[keyOf(selector)]?.getUserReason(),
      message)
  }

  private DependencyOutdated buildOutdatedDependency(Coordinate coordinate,
    Map<String, String> key) {
    VersionAvailable available

    String laterVersion = latestVersions[key]?.getVersion()
    switch (revision) {
      case "milestone":
        available = new VersionAvailable(null, laterVersion)
        break
      case "integration":
        available = new VersionAvailable(null, null, laterVersion)
        break
      default:
        available = new VersionAvailable(laterVersion)
    }

    return new DependencyOutdated(key["group"], key["name"], coordinate?.getVersion(),
      projectUrls[key], coordinate?.getUserReason(), available)
  }

  private static Map<Map<String, String>, Coordinate> sortByGroupAndName(
    Map<Map<String, String>, Coordinate> dependencies) {
    return dependencies.sort { Map.Entry<Map<String, String>, Coordinate> a,
      Map.Entry<Map<String, String>, Coordinate> b -> compareKeys(a.key, b.key)
    }
  }

  /** Compares the dependency keys. */
  private static int compareKeys(Map<String, String> a, Map<String, String> b) {
    return (a["group"] == b["group"]) ? a["name"] <=> b["name"] : a["group"] <=> b["group"]
  }

  private static Map<String, String> keyOf(ModuleVersionSelector dependency) {
    return [group: dependency.group, name: dependency.name]
  }
}
