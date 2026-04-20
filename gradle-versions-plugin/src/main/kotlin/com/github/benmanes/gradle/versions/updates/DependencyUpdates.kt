package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateChecker
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.artifacts.UnresolvedDependency
import org.gradle.api.logging.Logging
import java.io.File

/**
 * An evaluator for reporting of which dependencies have later versions.
 * <p>
 * The <tt>revision</tt> property controls the resolution strategy:
 * <ul>
 *   <li>release: selects the latest release
 *   <li>milestone: select the latest version being either a milestone or a release (default)
 *   <li>integration: selects the latest revision of the dependency module (such as SNAPSHOT)
 * </ul>
 */
class DependencyUpdates(
  val projectConfigs: List<ProjectConfigurations>,
  val buildscriptConfigs: List<ProjectConfigurations>,
  val projectDir: File,
  val projectPath: String,
  val resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?,
  val revision: String,
  private val outputFormatterArgument: OutputFormatterArgument,
  val outputDir: String,
  val reportfileName: String?,
  val checkForGradleUpdate: Boolean,
  val gradleVersionsApiBaseUrl: String,
  val gradleReleaseChannel: String,
  val checkConstraints: Boolean = false,
  val checkBuildEnvironmentConstraints: Boolean = false,
) {
  private val logger = Logging.getLogger(DependencyUpdates::class.java)

  /**
   * Resolves all project and buildscript dependencies and returns the status sets.
   * This requires project services (ConfigurationContainer, DependencyHandler) and must
   * be called during the configuration phase when those services are available.
   */
  fun resolveStatuses(): Pair<Set<DependencyStatus>, Set<DependencyStatus>> {
    val projectStatuses = resolveProjects(projectConfigs, checkConstraints)
    val buildscriptStatuses = resolveProjects(buildscriptConfigs, checkBuildEnvironmentConstraints)
    return Pair(projectStatuses, buildscriptStatuses)
  }

  /**
   * Builds a reporter from pre-resolved dependency statuses.
   * This is a pure data transformation that does not require project services,
   * so it can safely run at execution time.
   */
  fun createReporterFromStatuses(
    projectStatuses: Set<DependencyStatus>,
    buildscriptStatuses: Set<DependencyStatus>,
  ): DependencyUpdatesReporter {
    val statuses = projectStatuses + buildscriptStatuses
    val versions = VersionMapping(statuses)
    val unresolved = statuses.mapNotNullTo(mutableSetOf()) { it.unresolved }
    val projectUrls =
      statuses
        .filter { !it.projectUrl.isNullOrEmpty() }
        .associateBy(
          { mapOf("group" to it.coordinate.groupId, "name" to it.coordinate.artifactId) },
          { it.projectUrl.toString() },
        )
    return createReporter(versions, unresolved, projectUrls)
  }

  /**
   * Evaluates the project dependencies and then the buildScript dependencies to apply different
   * task options and returns a reporter for the results.
   */
  fun run(): DependencyUpdatesReporter {
    val (projectStatuses, buildscriptStatuses) = resolveStatuses()
    return createReporterFromStatuses(projectStatuses, buildscriptStatuses)
  }

  private fun resolveProjects(
    projectConfigs: List<ProjectConfigurations>,
    checkConstraints: Boolean,
  ): Set<DependencyStatus> {
    val resultStatus = hashSetOf<DependencyStatus>()
    for (entry in projectConfigs) {
      val resolver = Resolver(entry.context, resolutionStrategy, checkConstraints)
      for (currentConfiguration in entry.configurations) {
        if (currentConfiguration.isCanBeResolved) {
          for (newStatus in resolve(resolver, entry.context, currentConfiguration)) {
            addValidatedDependencyStatus(resultStatus, newStatus)
          }
        }
      }
    }
    return resultStatus
  }

  private fun resolve(
    resolver: Resolver,
    context: ProjectContext,
    config: org.gradle.api.artifacts.Configuration,
  ): Set<DependencyStatus> {
    return try {
      resolver.resolve(config, revision)
    } catch (e: Exception) {
      logger.info("Skipping configuration ${context.path}:${config.name}: ${e.javaClass.simpleName}: ${e.message}", e)
      emptySet()
    }
  }

  private fun createReporter(
    versions: VersionMapping,
    unresolved: Set<UnresolvedDependency>,
    projectUrls: Map<Map<String, String>, String>,
  ): DependencyUpdatesReporter {
    val currentVersions =
      versions.current
        .associateBy({ mapOf("group" to it.groupId, "name" to it.artifactId) }, { it })
    val latestVersions =
      versions.latest
        .associateBy({ mapOf("group" to it.groupId, "name" to it.artifactId) }, { it })
    val upToDateVersions =
      versions.upToDate
        .associateBy({ mapOf("group" to it.groupId, "name" to it.artifactId) }, { it })
    val downgradeVersions = toMap(versions.downgrade)
    val upgradeVersions = toMap(versions.upgrade)

    // Check for Gradle updates.
    val gradleUpdateChecker = GradleUpdateChecker(checkForGradleUpdate, gradleVersionsApiBaseUrl)

    return DependencyUpdatesReporter(
      projectDir, projectPath, revision, outputFormatterArgument, outputDir,
      reportfileName, currentVersions, latestVersions, upToDateVersions, downgradeVersions,
      upgradeVersions, versions.undeclared, unresolved, projectUrls, gradleUpdateChecker,
      gradleReleaseChannel,
    )
  }

  companion object {
    /**
     * A new status will be added if either,
     * <ol>
     *   <li>[Coordinate.Key] of new status is not yet present in status collection
     *   <li>new status has concrete version (not `none`); the old status will then be removed
     *       if its coordinate is `none` versioned</li>
     * </ol>
     */
    private fun addValidatedDependencyStatus(
      statusCollection: HashSet<DependencyStatus>,
      status: DependencyStatus,
    ) {
      val statusWithSameCoordinateKey =
        statusCollection.find {
          it.coordinate.key == status.coordinate.key
        }
      if (statusWithSameCoordinateKey == null) {
        statusCollection.add(status)
      } else if (status.coordinate.version != "none") {
        statusCollection.add(status)
        if (statusWithSameCoordinateKey.coordinate.version == "none") {
          statusCollection.remove(statusWithSameCoordinateKey)
        }
      }
    }

    private fun toMap(coordinates: Set<Coordinate>): Map<Map<String, String>, Coordinate> {
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
  }
}
