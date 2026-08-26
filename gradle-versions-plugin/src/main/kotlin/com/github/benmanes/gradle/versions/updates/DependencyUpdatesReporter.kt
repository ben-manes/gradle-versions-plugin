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
import com.github.benmanes.gradle.versions.reporter.result.SkippedConfiguration
import com.github.benmanes.gradle.versions.reporter.result.SkippedConfigurationsGroup
import com.github.benmanes.gradle.versions.reporter.result.VersionAvailable
import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel
import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateChecker
import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResult
import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResults
import org.gradle.api.logging.Logger
import java.io.File
import java.io.PrintStream
import java.util.TreeSet

/**
 * Sorts and writes the resolved dependency reports.
 *
 * @property projectPath The build tree path of the project evaluated against.
 * @property logger The logger to report generated report files with.
 * @property revision The revision strategy evaluated with.
 * @property outputFormatterArgument The output formatter strategy evaluated with.
 * @property outputDirectory The directory the report is written to.
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
 * @property latestByCurrent The latest version found for each declared version.
 * @property projectsByCoordinate The projects for each version of a coordinate whose versions
 * diverge.
 * @property contributedCoordinates The coordinates that only lazy actions contributed, no project declaring them.
 * @property configurationsByCoordinate The configurations a plugin contributed each marked coordinate into.
 * @property skipped The configurations whose dependencies could not be inspected because applying the
 * resolutionStrategy to them failed.
 * @property platformProjectsByCoordinate The platform projects the build imports each coordinate through.
 * @property constrainedByCoordinate The platforms that constrain each coordinate's version.
 *
 */
class DependencyUpdatesReporter(
  val projectPath: String,
  val logger: Logger,
  val revision: String,
  private val outputFormatterArgument: OutputFormatterArgument,
  val outputDirectory: File,
  val reportfileName: String?,
  val currentVersions: Map<Map<String, String>, Coordinate>,
  val latestVersions: Map<Map<String, String>, Coordinate>,
  val upToDateVersions: Map<Map<String, String>, Coordinate>,
  val downgradeVersions: Map<Map<String, String>, Coordinate>,
  val upgradeVersions: Map<Map<String, String>, Coordinate>,
  val undeclared: Set<Coordinate>,
  val unresolved: Set<UnresolvedInfo>,
  val projectUrls: Map<Map<String, String>, String>,
  val gradleUpdateChecker: GradleUpdateChecker,
  val gradleReleaseChannel: String,
  val latestByCurrent: Map<Coordinate, Coordinate> = emptyMap(),
  val projectsByCoordinate: Map<Coordinate, List<String>> = emptyMap(),
  val contributedCoordinates: Set<Coordinate> = emptySet(),
  val configurationsByCoordinate: Map<Coordinate, List<String>> = emptyMap(),
  val skipped: List<SkippedConfiguration> = emptyList(),
  val platformProjectsByCoordinate: Map<Coordinate, List<String>> = emptyMap(),
  val constrainedByCoordinate: Map<Coordinate, List<String>> = emptyMap(),
) {
  @Deprecated("Use the constructor that includes the skipped configurations.")
  constructor(
    projectPath: String,
    logger: Logger,
    revision: String,
    outputFormatterArgument: OutputFormatterArgument,
    outputDirectory: File,
    reportfileName: String?,
    currentVersions: Map<Map<String, String>, Coordinate>,
    latestVersions: Map<Map<String, String>, Coordinate>,
    upToDateVersions: Map<Map<String, String>, Coordinate>,
    downgradeVersions: Map<Map<String, String>, Coordinate>,
    upgradeVersions: Map<Map<String, String>, Coordinate>,
    undeclared: Set<Coordinate>,
    unresolved: Set<UnresolvedInfo>,
    projectUrls: Map<Map<String, String>, String>,
    gradleUpdateChecker: GradleUpdateChecker,
    gradleReleaseChannel: String,
    latestByCurrent: Map<Coordinate, Coordinate> = emptyMap(),
    projectsByCoordinate: Map<Coordinate, List<String>> = emptyMap(),
    contributedCoordinates: Set<Coordinate> = emptySet(),
    configurationsByCoordinate: Map<Coordinate, List<String>> = emptyMap(),
  ) : this(
    projectPath, logger, revision, outputFormatterArgument, outputDirectory, reportfileName,
    currentVersions, latestVersions, upToDateVersions, downgradeVersions, upgradeVersions,
    undeclared, unresolved, projectUrls, gradleUpdateChecker, gradleReleaseChannel,
    latestByCurrent, projectsByCoordinate, contributedCoordinates, configurationsByCoordinate,
    emptyList(),
  )

  @Synchronized
  fun write() {
    if (outputFormatterArgument !is OutputFormatterArgument.CustomAction && logger.isLifecycleEnabled) {
      val plainTextReporter =
        PlainTextReporter(
          projectPath,
          revision,
          gradleReleaseChannel,
          logger.isInfoEnabled,
        )
      plainTextReporter.write(System.out, buildBaseObject())
    }

    if (outputFormatterArgument is OutputFormatterArgument.BuiltIn && outputFormatterArgument.formatterNames.isEmpty()) {
      logger.info("Skip generating report to file (outputFormatter is empty)")
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
    outputDirectory.mkdirs()
    val outputFile = File(outputDirectory, reportfileName + "." + reporter.getFileExtension())
    val stream = PrintStream(outputFile)
    val result = buildBaseObject()
    reporter.write(stream, result)
    stream.close()

    logger.lifecycle("\nGenerated report file $outputFile")
  }

  private fun getOutputReporter(formatterOriginal: String): Reporter {
    return when (formatterOriginal.trim()) {
      "json" -> JsonReporter(projectPath, revision, gradleReleaseChannel)
      "xml" -> XmlReporter(projectPath, revision, gradleReleaseChannel)
      "html" -> HtmlReporter(projectPath, revision, gradleReleaseChannel)
      else -> PlainTextReporter(projectPath, revision, gradleReleaseChannel, logger.isInfoEnabled)
    }
  }

  private fun buildBaseObject(): Result {
    val sortedCurrent = buildCurrentGroup()
    val sortedOutdated = buildOutdatedGroup()
    val sortedExceeded = buildExceededGroup()
    val sortedUndeclared = buildUndeclaredGroup()
    val sortedUnresolved = buildUnresolvedGroup()
    val sortedSkipped = skipped.sorted()

    val count =
      sortedCurrent.size +
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
      skippedGroup = SkippedConfigurationsGroup(sortedSkipped.size, sortedSkipped),
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
      running =
        GradleUpdateResult(
          enabled = enabled,
          running = gradleUpdateChecker.getRunningGradleVersion(),
          release = gradleUpdateChecker.getRunningGradleVersion(),
        ),
      current =
        GradleUpdateResult(
          enabled = enabled,
          running = gradleUpdateChecker.getRunningGradleVersion(),
          release = gradleUpdateChecker.getCurrentGradleVersion(),
        ),
      releaseCandidate =
        GradleUpdateResult(
          enabled =
            enabled &&
              (
                gradleReleaseChannel == GradleReleaseChannel.RELEASE_CANDIDATE.id ||
                  gradleReleaseChannel == GradleReleaseChannel.NIGHTLY.id
              ),
          running = gradleUpdateChecker.getRunningGradleVersion(),
          release = gradleUpdateChecker.getReleaseCandidateGradleVersion(),
        ),
      nightly =
        GradleUpdateResult(
          enabled = enabled && (gradleReleaseChannel == GradleReleaseChannel.NIGHTLY.id),
          running = gradleUpdateChecker.getRunningGradleVersion(),
          release = gradleUpdateChecker.getNightlyGradleVersion(),
        ),
    )
  }

  private fun buildCurrentGroup(): MutableSet<Dependency> {
    return sortByGroupAndName(upToDateVersions)
      .map { dep -> buildDependency(dep.value, strippedKey(dep.key)) }
      .toSortedSet()
  }

  private fun buildOutdatedGroup(): MutableSet<DependencyOutdated> {
    return sortByGroupAndName(upgradeVersions)
      .map { dep -> buildOutdatedDependency(dep.value, strippedKey(dep.key)) }
      .toSortedSet()
  }

  private fun buildExceededGroup(): MutableSet<DependencyLatest> {
    return sortByGroupAndName(downgradeVersions)
      .map { dep -> buildExceededDependency(dep.value, strippedKey(dep.key)) }
      .toSortedSet()
  }

  private fun buildUndeclaredGroup(): MutableSet<Dependency> {
    return undeclared
      .map { coordinate ->
        Dependency(coordinate.groupId, coordinate.artifactId)
      }.toSortedSet()
  }

  private fun buildUnresolvedGroup(): MutableSet<DependencyUnresolved> {
    return unresolved
      .sortedWith { a, b -> compareKeys(keyOf(a), keyOf(b)) }
      .map { dep -> buildUnresolvedDependency(dep) }
      .toSortedSet() as TreeSet<DependencyUnresolved>
  }

  private fun buildDependency(
    coordinate: Coordinate,
    key: Map<String, String>,
  ): Dependency {
    return Dependency(
      group = key["group"],
      name = key["name"],
      version = coordinate.version,
      projectUrl = projectUrls[key],
      userReason = coordinate.userReason,
      projects = projectsByCoordinate[coordinate],
      contributed = contributedFlag(coordinate),
      configurations = configurationsByCoordinate[coordinate],
      platformProjects = platformProjectsByCoordinate[coordinate],
      constrainedBy = constrainedByCoordinate[coordinate],
    )
  }

  private fun buildExceededDependency(
    coordinate: Coordinate,
    key: Map<String, String>,
  ): DependencyLatest {
    return DependencyLatest(
      group = key["group"],
      name = key["name"],
      version = coordinate.version,
      projectUrl = projectUrls[key],
      userReason = coordinate.userReason,
      latest = latestFor(coordinate, key).orEmpty(),
      projects = projectsByCoordinate[coordinate],
      contributed = contributedFlag(coordinate),
      configurations = configurationsByCoordinate[coordinate],
      platformProjects = platformProjectsByCoordinate[coordinate],
      constrainedBy = constrainedByCoordinate[coordinate],
    )
  }

  /** Returns true when the coordinate was only contributed by a plugin, otherwise null. */
  private fun contributedFlag(coordinate: Coordinate): Boolean? = if (coordinate in contributedCoordinates) true else null

  /** Returns the latest version found for the declared version, if it was paired with one. */
  private fun latestFor(
    coordinate: Coordinate,
    key: Map<String, String>,
  ): String? {
    return (latestByCurrent[coordinate] ?: latestVersions[key])?.version
  }

  private fun buildUnresolvedDependency(info: UnresolvedInfo): DependencyUnresolved {
    val declared = Coordinate(info.selectorGroup, info.selectorName, info.declaredVersion)
    return DependencyUnresolved(
      group = info.selectorGroup,
      name = info.selectorName,
      version = info.declaredVersion,
      projectUrl = projectUrls[keyOf(info)],
      userReason = info.userReason,
      reason = info.failureText,
      contributed = contributedFlag(declared),
      configurations = configurationsByCoordinate[declared],
      platformProjects = platformProjectsByCoordinate[declared],
      constrainedBy = constrainedByCoordinate[declared],
    )
  }

  private fun buildOutdatedDependency(
    coordinate: Coordinate,
    key: Map<String, String>,
  ): DependencyOutdated {
    val laterVersion = latestFor(coordinate, key)
    val available =
      when (revision) {
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
      projects = projectsByCoordinate[coordinate],
      contributed = contributedFlag(coordinate),
      configurations = configurationsByCoordinate[coordinate],
      platformProjects = platformProjectsByCoordinate[coordinate],
      constrainedBy = constrainedByCoordinate[coordinate],
    )
  }

  companion object {
    /** Returns the key with the disambiguating suffix that [toMap] appended removed. */
    private fun strippedKey(existingKey: Map<String, String>): Map<String, String> {
      val name = existingKey["name"].orEmpty()
      val index = name.lastIndexOf("[")
      return if (index == -1) existingKey else existingKey + ("name" to name.substring(0, index))
    }

    private fun buildObject(
      count: Int,
      currentGroup: DependenciesGroup<Dependency>,
      outdatedGroup: DependenciesGroup<DependencyOutdated>,
      exceededGroup: DependenciesGroup<DependencyLatest>,
      undeclaredGroup: DependenciesGroup<Dependency>,
      unresolvedGroup: DependenciesGroup<DependencyUnresolved>,
      gradleUpdateResults: GradleUpdateResults,
      skippedGroup: SkippedConfigurationsGroup,
    ): Result {
      return Result(
        count = count,
        current = currentGroup,
        outdated = outdatedGroup,
        exceeded = exceededGroup,
        undeclared = undeclaredGroup,
        unresolved = unresolvedGroup,
        gradle = gradleUpdateResults,
        skipped = skippedGroup,
      )
    }

    private fun <T : Dependency> buildDependenciesGroup(dependencies: MutableSet<T>): DependenciesGroup<T> {
      return DependenciesGroup(dependencies.size, dependencies)
    }

    private fun sortByGroupAndName(dependencies: Map<Map<String, String>, Coordinate>): Map<Map<String, String>, Coordinate> {
      return dependencies.toSortedMap { a, b ->
        compareKeys(a, b)
      }
    }

    /** Compares the dependency keys. */
    private fun compareKeys(
      a: Map<String, String>,
      b: Map<String, String>,
    ): Int {
      return if (a["group"] == b["group"]) {
        a["name"].orEmpty().compareTo(b["name"].orEmpty())
      } else {
        a["group"].orEmpty().compareTo(b["group"].orEmpty())
      }
    }

    private fun keyOf(info: UnresolvedInfo): Map<String, String> {
      return mapOf("group" to info.selectorGroup, "name" to info.selectorName)
    }
  }
}

/** Returns a reporter for the merged statuses of one or more projects. */
@Suppress("LongParameterList")
@JvmOverloads
fun reporterFor(
  statuses: List<PartialStatus>,
  projectPath: String,
  logger: Logger,
  revision: String,
  outputFormatterArgument: OutputFormatterArgument,
  outputDir: File,
  reportfileName: String?,
  checkForGradleUpdate: Boolean,
  gradleVersionsApiBaseUrl: String,
  gradleReleaseChannel: String,
  skipped: List<SkippedConfiguration> = emptyList(),
): DependencyUpdatesReporter {
  val split = markDivergentlyResolved(statuses)
  val versions = VersionMapping(logger, split)
  val projectsByCoordinate = divergentProjects(split)
  val contributedCoordinates = contributedCoordinates(split, logger)
  val configurationsByCoordinate = namedConfigurations(split, contributedCoordinates)
  val platformProjectsByCoordinate = platformProjectsByCoordinate(split, logger)
  val constrainedByCoordinate = constrainedByCoordinate(split, logger)
  val unresolved = split.mapNotNullTo(mutableSetOf()) { it.unresolved }
  val projectUrls =
    split
      .filter { !it.projectUrl.isNullOrEmpty() }
      .associateBy(
        { mapOf("group" to it.group, "name" to it.name) },
        { it.projectUrl.toString() },
      )

  val currentVersions = toKeyedMap(versions.current)
  val latestVersions =
    versions.latest
      .associateBy({ mapOf("group" to it.groupId, "name" to it.artifactId) }, { it })
  val upToDateVersions = toKeyedMap(versions.upToDate)
  val downgradeVersions = toKeyedMap(versions.downgrade)
  val upgradeVersions = toKeyedMap(versions.upgrade)

  // Check for Gradle updates.
  val gradleUpdateChecker = GradleUpdateChecker(checkForGradleUpdate, gradleVersionsApiBaseUrl)

  return DependencyUpdatesReporter(
    projectPath, logger, revision, outputFormatterArgument, outputDir,
    reportfileName, currentVersions, latestVersions, upToDateVersions, downgradeVersions,
    upgradeVersions, versions.undeclared, unresolved, projectUrls, gradleUpdateChecker,
    gradleReleaseChannel, versions.latestByCurrent, projectsByCoordinate, contributedCoordinates,
    configurationsByCoordinate, skipped, platformProjectsByCoordinate, constrainedByCoordinate,
  )
}

/** Returns the coordinates that only lazy actions contributed, with no project declaring them. */
private fun contributedCoordinates(
  statuses: List<PartialStatus>,
  logger: Logger,
): Set<Coordinate> {
  // Grouped by the declaration rather than by the coordinate, so that splitting a row does not put
  // two disagreeing projects into groups that are each unanimous on their own.
  val byDeclaration = statuses.groupBy { Declaration(it) }
  // Left out when the projects disagree, which is a mark that cannot be trusted rather than one
  // that does not apply, so it is reported instead of passing as an ordinary unmarked entry.
  for ((declaration, group) in byDeclaration) {
    if (group.any { it.contributed } && !group.all { it.contributed }) {
      logger.info(
        "The projects disagree on whether a plugin contributed ${declaration.group}:" +
          "${declaration.name}, so it is left unmarked: contributed by " +
          group.filter { it.contributed }.mapNotNull { it.projectPath }.sorted().joinToString(", "),
      )
    }
  }
  return byDeclaration
    .filterValues { group -> group.all { it.contributed } }
    .values
    .flatMapTo(mutableSetOf()) { group -> group.map { it.coordinate } }
}

/** One module at one declared version, which a split leaves as several coordinates. */
private data class Declaration(val group: String, val name: String, val declaredVersion: String) {
  constructor(status: PartialStatus) : this(status.group, status.name, status.declaredVersion)
}

/**
 * Returns the configurations printed for each coordinate, taken across the projects that observed
 * it, so that a plugin which fills a differently named configuration per project is reported as
 * filling both rather than either.
 *
 * For a marked coordinate, every configuration seen is used, as the mark already shows that a
 * plugin put it there. A configuration is printed for an unmarked one only where every
 * project that observed it declared it against a configuration of its own, so that a project
 * declaring it ordinarily is not described as declaring it somewhere it did not. A project reaches
 * the same dependency through every resolvable configuration that extends the one it is declared
 * on, so naming one of them is enough for that project.
 */
private fun namedConfigurations(
  statuses: List<PartialStatus>,
  contributed: Set<Coordinate>,
): Map<Coordinate, List<String>> =
  statuses
    // Grouped by the declaration for the same reason as the marks above: whether a configuration
    // was named in every observing project is a question about all of them, which a split narrows.
    .groupBy { Declaration(it) }
    .filter { (_, group) ->
      group.any { it.coordinate in contributed } ||
        group.groupBy { it.projectPath }.values.all { project ->
          project.any { it.configurations.isNotEmpty() }
        }
    }.flatMap { (_, group) ->
      val names = group.flatMap { it.configurations }.distinct().sorted()
      group.map { it.coordinate to names }
    }.toMap()
    .filterValues { it.isNotEmpty() }

/**
 * Returns the platform projects each coordinate was imported through, unioned across projects.
 * Left out when a project outside that importer set declares the coordinate, since a declaration
 * anywhere else is the row to edit and the mark would point somewhere else. A declaring project
 * that is itself one of the named importers is not a disagreement: the mark already points at it.
 */
private fun platformProjectsByCoordinate(
  statuses: List<PartialStatus>,
  logger: Logger,
): Map<Coordinate, List<String>> =
  statuses
    .groupBy { it.coordinate }
    .mapNotNull { (coordinate, group) ->
      val importers = group.flatMap { it.platformProjects }.toSet()
      if (importers.isEmpty()) {
        return@mapNotNull null
      }
      val declaringStatuses =
        group.filter {
          it.platformProjects.isEmpty() && it.constrainedBy.isEmpty() && !it.contributed &&
            it.projectPath !in importers
        }
      if (declaringStatuses.isNotEmpty()) {
        val declaringPaths = declaringStatuses.mapNotNull { it.projectPath }.distinct().sorted()
        logger.info(
          "A project outside ${coordinate.groupId}:${coordinate.artifactId}'s platform importers " +
            "declares it, so the platform mark is withheld: ${declaringPaths.joinToString(", ")}",
        )
        return@mapNotNull null
      }
      coordinate to importers.toList().sorted()
    }.toMap()

/**
 * Returns the platforms that constrain each coordinate's version, unioned across projects. Left out
 * when another project declares the coordinate with no platform constraining it there, since that
 * declaration is the row to edit and the mark would point somewhere else.
 */
private fun constrainedByCoordinate(
  statuses: List<PartialStatus>,
  logger: Logger,
): Map<Coordinate, List<String>> =
  statuses
    .groupBy { it.coordinate }
    .mapNotNull { (coordinate, group) ->
      val sources = group.flatMap { it.constrainedBy }.toSet()
      if (sources.isEmpty()) {
        return@mapNotNull null
      }
      val declaringStatuses =
        group.filter {
          it.constrainedBy.isEmpty() && it.platformProjects.isEmpty() && !it.contributed &&
            it.projectPath !in sources
        }
      if (declaringStatuses.isNotEmpty()) {
        val declaringPaths = declaringStatuses.mapNotNull { it.projectPath }.distinct().sorted()
        logger.info(
          "A project outside ${coordinate.groupId}:${coordinate.artifactId}'s bounding platforms " +
            "declares it, so the platform mark is withheld: ${declaringPaths.joinToString(", ")}",
        )
        return@mapNotNull null
      }
      coordinate to sources.toList().sorted()
    }.toMap()

/**
 * Returns the statuses with the rows of a declared version whose latest version differs across the
 * aggregated projects marked, so that each of those latest versions is printed on a row of its own.
 *
 * The marked rows are returned as copies and the argument is left alone, since this is a published
 * entry point and the statuses a caller passes are its own.
 *
 * Merged, the newest of them is printed for every project, including the ones it is not available
 * in. That is what happens when a platform bounds the module in one project and not in another.
 * The rows are separated by the latest version rather than by the project, so that the projects
 * which agree are printed on one row and included on it together, exactly as they are when their
 * declared versions differ.
 *
 * An unresolved row has no latest version to be split by and is left whole. So is a coordinate with
 * two latest versions inside a single project, which happens when its buildscript and its
 * dependencies use different repositories. Splitting that one would leave two rows with the same
 * project name, which the report's sorted groups compare as equal and drop, so this exclusion is
 * what keeps a split row's projects disjoint.
 */
private fun markDivergentlyResolved(statuses: List<PartialStatus>): List<PartialStatus> {
  if (statuses.mapTo(mutableSetOf()) { it.projectPath }.size <= 1) {
    return statuses
  }
  val marked =
    statuses
      .withIndex()
      .filter { (_, status) -> status.projectPath != null && status.unresolved == null }
      .groupBy { (_, status) -> status.coordinate }
      .values
      .filter { statusesOfVersion ->
        val byProject = statusesOfVersion.groupBy { (_, status) -> status.projectPath }
        byProject.size > 1 &&
          byProject.values.all { rows -> rows.mapTo(mutableSetOf()) { it.value.latestVersion }.size == 1 } &&
          byProject.values.mapTo(mutableSetOf()) { it.first().value.latestVersion }.size > 1
      }.flatten()
      .mapTo(mutableSetOf()) { it.index }
  if (marked.isEmpty()) {
    return statuses
  }
  return statuses.mapIndexed { index, status ->
    if (index in marked) status.copy(splitByLatest = true) else status
  }
}

/**
 * Returns the projects for each version of a key whose declared versions differ, or whose one
 * declared version has different latest versions across the projects.
 */
private fun divergentProjects(statuses: List<PartialStatus>): Map<Coordinate, List<String>> {
  if (statuses.mapTo(mutableSetOf()) { it.projectPath }.size <= 1) {
    return emptyMap()
  }
  return statuses
    .filter { it.projectPath != null }
    .groupBy { Coordinate.Key(it.group, it.name) }
    .values
    .filter { statusesOfKey ->
      statusesOfKey.mapTo(mutableSetOf()) { it.declaredVersion }.size > 1 ||
        statusesOfKey.any { it.splitByLatest }
    }.flatten()
    .groupBy({ it.coordinate }, { it.projectPath!! })
    .mapValues { (_, paths) -> paths.distinct().sorted() }
}

/** Returns the coordinates keyed by their group and name, disambiguating a repeated name. */
private fun toKeyedMap(coordinates: Set<Coordinate>): Map<Map<String, String>, Coordinate> {
  val map = HashMap<Map<String, String>, Coordinate>()
  for (coordinate in coordinates) {
    var i = 0
    while (true) {
      val artifactId = coordinate.artifactId + if (i == 0) "" else "[${i + 1}]"
      val keyMap =
        linkedMapOf<String, String>().apply {
          put("group", coordinate.groupId)
          put("name", artifactId)
        }
      if (!map.containsKey(keyMap)) {
        map[keyMap] = coordinate
        break
      }

      ++i
    }
  }
  return map
}
