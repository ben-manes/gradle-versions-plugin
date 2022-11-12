package com.github.benmanes.gradle.versions.updates

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
import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel
import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateChecker
import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResult
import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResults
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleVersionSelector
import org.gradle.api.artifacts.UnresolvedDependency
import java.io.File
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.TreeSet

/**
 * Sorts and writes the resolved dependency reports.
 *
 * @property project The project evaluated against.
 * @property revision The revision strategy evaluated with.
 * @property outputFormatterArgument The output formatter strategy evaluated with.
 * @property outputDir The outputDir for report.
 * @property reportfileName The filename of the report file.
 * @property currentVersions The current versions of each dependency declared in the project(s).
 * @property latestVersions The latest versions of each dependency (as scoped by the revision level).
 * @property upToDateVersions The dependencies that are up to date (same as latest found).
 * @property downgradeVersions The dependencies that exceed the latest found (e.g. may not want SNAPSHOTs).
 * @property upgradeVersions The dependencies where upgrades were found (below latest found).
 * @property undeclared The dependencies that were declared without version.
 * @property unresolved The dependencies that could not be resolved.
 * @property projectUrls Project urls of maven dependencies.
 * @property gradleUpdateChecker Facade object to access information about running gradle versions
 * and gradle updates.
 * @property gradleReleaseChannel The gradle release channel to use for reporting.
 *
 */
class DependencyUpdatesReporter(
  val project: Project,
  val revision: String,
  private val outputFormatterArgument: OutputFormatterArgument,
  val outputDir: String,
  val reportfileName: String?,
  val currentVersions: Map<Map<String, String>, Coordinate>,
  val latestVersions: Map<Map<String, String>, Coordinate>,
  val upToDateVersions: Map<Map<String, String>, Coordinate>,
  val downgradeVersions: Map<Map<String, String>, Coordinate>,
  val upgradeVersions: Map<Map<String, String>, Coordinate>,
  val undeclared: Set<Coordinate>,
  val unresolved: Set<UnresolvedDependency>,
  val projectUrls: Map<Map<String, String>, String>,
  val gradleUpdateChecker: GradleUpdateChecker,
  val gradleReleaseChannel: String,
) {

  @Synchronized
  fun write() {
    if (outputFormatterArgument !is OutputFormatterArgument.CustomAction) {
      val plainTextReporter = PlainTextReporter(
        project, revision, gradleReleaseChannel
      )
      plainTextReporter.write(System.out, buildBaseObject())
    }

    if (outputFormatterArgument is OutputFormatterArgument.BuiltIn && outputFormatterArgument.formatterNames.isEmpty()) {
      project.logger.lifecycle("Skip generating report to file (outputFormatter is empty)")
      return
    }

    when (outputFormatterArgument) {
      is OutputFormatterArgument.BuiltIn -> {
        for (it in outputFormatterArgument.formatterNames.split(",")) {
          generateFileReport(getOutputReporter(it))
        }
      }

      is OutputFormatterArgument.CustomReporter -> {
        generateFileReport(outputFormatterArgument.reporter)
      }

      is OutputFormatterArgument.CustomAction -> {
        val result = buildBaseObject()
        outputFormatterArgument.action.execute(result)
      }
    }
  }

  private fun generateFileReport(reporter: Reporter) {
    val fileName = File(outputDir, reportfileName + "." + reporter.getFileExtension())
    project.file(outputDir).mkdirs()
    val outputFile = project.file(fileName)
    val stream = PrintStream(outputFile)
    val result = buildBaseObject()
    reporter.write(stream, result)
    stream.close()

    project.logger.lifecycle("\nGenerated report file $fileName")
  }

  private fun getOutputReporter(formatterOriginal: String): Reporter {
    return when (formatterOriginal.trim()) {
      "json" -> JsonReporter(project, revision, gradleReleaseChannel)
      "xml" -> XmlReporter(project, revision, gradleReleaseChannel)
      "html" -> HtmlReporter(project, revision, gradleReleaseChannel)
      else -> PlainTextReporter(project, revision, gradleReleaseChannel)
    }
  }

  private fun buildBaseObject(): Result {
    val sortedCurrent = buildCurrentGroup()
    val sortedOutdated = buildOutdatedGroup()
    val sortedExceeded = buildExceededGroup()
    val sortedUndeclared = buildUndeclaredGroup()
    val sortedUnresolved = buildUnresolvedGroup()

    val count = sortedCurrent.size +
      sortedOutdated.size +
      sortedExceeded.size +
      sortedUndeclared.size +
      sortedUnresolved.size

    return buildObject(
      count = count,
      currentGroup = buildDependenciesGroup(sortedCurrent),
      outdatedGroup = buildDependenciesGroup(sortedOutdated),
      exceededGroup = buildDependenciesGroup(sortedExceeded),
      undeclaredGroup = buildDependenciesGroup(sortedUndeclared),
      unresolvedGroup = buildDependenciesGroup(sortedUnresolved),
      gradleUpdateResults = buildGradleUpdateResults(),
    )
  }

  /**
   * Create a [GradleUpdateResults] object from the information provided by the [GradleUpdateChecker]
   * @return filled out object instance
   */
  private fun buildGradleUpdateResults(): GradleUpdateResults {
    val enabled = gradleUpdateChecker.enabled
    return GradleUpdateResults(
      enabled = enabled,
      running = GradleUpdateResult(
        enabled = enabled,
        running = gradleUpdateChecker.getRunningGradleVersion(),
        release = gradleUpdateChecker.getRunningGradleVersion(),
      ),
      current = GradleUpdateResult(
        enabled = enabled,
        running = gradleUpdateChecker.getRunningGradleVersion(),
        release = gradleUpdateChecker.getCurrentGradleVersion(),
      ),
      releaseCandidate = GradleUpdateResult(
        enabled = enabled &&
          (
            gradleReleaseChannel == GradleReleaseChannel.RELEASE_CANDIDATE.id ||
              gradleReleaseChannel == GradleReleaseChannel.NIGHTLY.id
            ),
        running = gradleUpdateChecker.getRunningGradleVersion(),
        release = gradleUpdateChecker.getReleaseCandidateGradleVersion(),
      ),
      nightly = GradleUpdateResult(
        enabled = enabled && (gradleReleaseChannel == GradleReleaseChannel.NIGHTLY.id),
        running = gradleUpdateChecker.getRunningGradleVersion(),
        release = gradleUpdateChecker.getNightlyGradleVersion(),
      ),
    )
  }

  private fun buildCurrentGroup(): Set<Dependency> {
    return sortByGroupAndName(upToDateVersions)
      .map { dep ->
        updateKey(dep.key as HashMap)
        buildDependency(dep.value, dep.key)
      }.toSortedSet() as TreeSet<Dependency>
  }

  private fun buildOutdatedGroup(): Set<DependencyOutdated> {
    return sortByGroupAndName(upgradeVersions)
      .map { dep ->
        updateKey(dep.key as HashMap)
        buildOutdatedDependency(dep.value, dep.key)
      }.toSortedSet() as TreeSet<DependencyOutdated>
  }

  private fun buildExceededGroup(): Set<DependencyLatest> {
    return sortByGroupAndName(downgradeVersions)
      .map { dep ->
        updateKey(dep.key as HashMap)
        buildExceededDependency(dep.value, dep.key)
      }.toSortedSet() as TreeSet<DependencyLatest>
  }

  private fun buildUndeclaredGroup(): Set<Dependency> {
    return undeclared
      .map { coordinate ->
        Dependency(coordinate.groupId, coordinate.artifactId)
      }.toSortedSet() as TreeSet<Dependency>
  }

  private fun buildUnresolvedGroup(): Set<DependencyUnresolved> {
    return unresolved
      .sortedWith { a, b ->
        compareKeys(keyOf(a.selector), keyOf(b.selector))
      }.map { dep ->
        val stringWriter = StringWriter()
        dep.problem.printStackTrace(PrintWriter(stringWriter))
        val message = stringWriter.toString()

        buildUnresolvedDependency(dep.selector, message)
      }.toSortedSet() as TreeSet<DependencyUnresolved>
  }

  private fun buildDependency(
    coordinate: Coordinate,
    key: Map<String, String>
  ): Dependency {
    return Dependency(
      group = key["group"],
      name = key["name"],
      version = coordinate.version,
      projectUrl = projectUrls[key],
      userReason = coordinate.userReason,
    )
  }

  private fun buildExceededDependency(
    coordinate: Coordinate,
    key: Map<String, String>
  ): DependencyLatest {
    return DependencyLatest(
      group = key["group"],
      name = key["name"],
      version = coordinate.version,
      projectUrl = projectUrls[key],
      userReason = coordinate.userReason,
      latest = latestVersions[key]?.version.orEmpty(),
    )
  }

  private fun buildUnresolvedDependency(
    selector: ModuleVersionSelector,
    message: String
  ): DependencyUnresolved {
    return DependencyUnresolved(
      group = selector.group,
      name = selector.name,
      version = currentVersions[keyOf(selector)]?.version,
      projectUrl = latestVersions[keyOf(selector)]?.version, // TODO not sure?
      userReason = currentVersions[keyOf(selector)]?.userReason,
      reason = message,
    )
  }

  private fun buildOutdatedDependency(
    coordinate: Coordinate,
    key: Map<String, String>
  ): DependencyOutdated {
    val laterVersion = latestVersions[key]?.version
    val available = when (revision) {
      "milestone" -> VersionAvailable(milestone = laterVersion)
      "integration" -> VersionAvailable(integration = laterVersion)
      else -> VersionAvailable(release = laterVersion)
    }
    return DependencyOutdated(
      group = key["group"],
      name = key["name"],
      version = coordinate.version,
      projectUrl = projectUrls[key],
      userReason = coordinate.userReason,
      available = available,
    )
  }

  companion object {
    private fun updateKey(existingKey: HashMap<String, String>) {
      val index = existingKey["name"]?.lastIndexOf("[") ?: -1
      if (index == -1) {
        existingKey["name"] = existingKey["name"].orEmpty()
      } else {
        existingKey["name"] = existingKey["name"].orEmpty().substring(0, index)
      }
    }

    private fun buildObject(
      count: Int,
      currentGroup: DependenciesGroup<Dependency>,
      outdatedGroup: DependenciesGroup<DependencyOutdated>,
      exceededGroup: DependenciesGroup<DependencyLatest>,
      undeclaredGroup: DependenciesGroup<Dependency>,
      unresolvedGroup: DependenciesGroup<DependencyUnresolved>,
      gradleUpdateResults: GradleUpdateResults,
    ): Result {
      return Result(
        count = count,
        current = currentGroup,
        outdated = outdatedGroup,
        exceeded = exceededGroup,
        undeclared = undeclaredGroup,
        unresolved = unresolvedGroup,
        gradle = gradleUpdateResults
      )
    }

    private fun <T : Dependency> buildDependenciesGroup(dependencies: Set<T>): DependenciesGroup<T> {
      return DependenciesGroup<T>(dependencies.size, dependencies)
    }

    private fun sortByGroupAndName(
      dependencies: Map<Map<String, String>, Coordinate>
    ): Map<Map<String, String>, Coordinate> {
      return dependencies.toSortedMap { a, b ->
        compareKeys(a, b)
      }
    }

    /** Compares the dependency keys. */
    private fun compareKeys(a: Map<String, String>, b: Map<String, String>): Int {
      return if (a["group"] == b["group"]) {
        a["name"].orEmpty().compareTo(b["name"].orEmpty())
      } else {
        a["group"].orEmpty().compareTo(b["group"].orEmpty())
      }
    }

    private fun keyOf(dependency: ModuleVersionSelector): Map<String, String> {
      return mapOf("group" to dependency.group, "name" to dependency.name)
    }
  }
}
