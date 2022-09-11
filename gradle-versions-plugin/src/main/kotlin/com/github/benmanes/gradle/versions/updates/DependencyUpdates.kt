package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateChecker
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.UnresolvedDependency

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
internal class DependencyUpdates @JvmOverloads constructor(
  val project: Project,
  val resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?,
  val revision: String,
  private val outputFormatterArgument: OutputFormatterArgument,
  val outputDir: String,
  val reportfileName: String?,
  val checkForGradleUpdate: Boolean,
  val gradleReleaseChannel: String,
  val checkConstraints: Boolean = false,
  val checkBuildEnvironmentConstraints: Boolean = false,
) {

  /**
   * Evaluates the project dependencies and then the buildScript dependencies to apply different
   * task options and returns a reporter for the results.
   */
  fun run(): DependencyUpdatesReporter {
    val projectConfigs = project.allprojects
      .associateBy({ it }, { it.configurations.toLinkedHashSet() })
    val status: Set<DependencyStatus> = resolveProjects(projectConfigs, checkConstraints)

    val buildscriptProjectConfigs = project.allprojects
      .associateBy({ it }, { it.buildscript.configurations.toLinkedHashSet() })
    val buildscriptStatus: Set<DependencyStatus> = resolveProjects(
      buildscriptProjectConfigs, checkBuildEnvironmentConstraints
    )

    val statuses = status + buildscriptStatus
    val versions = VersionMapping(project, statuses)
    val unresolved = statuses
      .filter { it.unresolved != null }
      .map { it.unresolved }
      .toSet() as Set<UnresolvedDependency>
    val projectUrls = statuses
      .filter { !it.projectUrl.isNullOrEmpty() }
      .associateBy(
        { mapOf("group" to it.coordinate.groupId, "name" to it.coordinate.artifactId) },
        { it.projectUrl.toString() }
      )

    return createReporter(versions, unresolved, projectUrls)
  }

  private fun resolveProjects(
    projectConfigs: Map<Project, Set<Configuration>>,
    checkConstraints: Boolean,
  ): Set<DependencyStatus> {
    val resultStatus = hashSetOf<DependencyStatus>()
    projectConfigs.forEach { (currentProject, currentConfigurations) ->
      val resolver = Resolver(currentProject, resolutionStrategy, checkConstraints)
      for (currentConfiguration in currentConfigurations) {
        for (newStatus in resolve(resolver, currentProject, currentConfiguration)) {
          addValidatedDependencyStatus(resultStatus, newStatus)
        }
      }
    }
    return resultStatus
  }

  private fun resolve(
    resolver: Resolver,
    project: Project,
    config: Configuration,
  ): Set<DependencyStatus> {
    return try {
      resolver.resolve(config, revision)
    } catch (e: Exception) {
      project.logger.info("Skipping configuration ${project.path}:${config.name}", e)
      emptySet()
    }
  }

  private fun createReporter(
    versions: VersionMapping,
    unresolved: Set<UnresolvedDependency>,
    projectUrls: Map<Map<String, String>, String>,
  ): DependencyUpdatesReporter {
    val currentVersions = versions.current
      .associateBy({ mapOf("group" to it.groupId, "name" to it.artifactId) }, { it })
    val latestVersions = versions.latest
      .associateBy({ mapOf("group" to it.groupId, "name" to it.artifactId) }, { it })
    val upToDateVersions = versions.upToDate
      .associateBy({ mapOf("group" to it.groupId, "name" to it.artifactId) }, { it })
    val downgradeVersions = toMap(versions.downgrade)
    val upgradeVersions = toMap(versions.upgrade)

    // Check for Gradle updates.
    val gradleUpdateChecker = GradleUpdateChecker(checkForGradleUpdate)

    return DependencyUpdatesReporter(
      project, revision, outputFormatterArgument, outputDir,
      reportfileName, currentVersions, latestVersions, upToDateVersions, downgradeVersions,
      upgradeVersions, versions.undeclared, unresolved, projectUrls, gradleUpdateChecker,
      gradleReleaseChannel
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
      val statusWithSameCoordinateKey = statusCollection.find {
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
          val keyMap = linkedMapOf<String, String>().apply {
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

private fun <T> Collection<T>.toLinkedHashSet(): LinkedHashSet<T> {
  return toCollection(LinkedHashSet<T>(this.size))
}
