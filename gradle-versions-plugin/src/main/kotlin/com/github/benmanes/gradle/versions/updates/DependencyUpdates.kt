package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateChecker
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.logging.Logger
import org.gradle.api.specs.Spec
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
class DependencyUpdates
  @JvmOverloads
  constructor(
    val project: Project,
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
    val filterConfigurations: Spec<Configuration> = Spec<Configuration> { true },
  ) {
    /**
     * Evaluates the project dependencies and then the buildScript dependencies to apply different
     * task options and returns a reporter for the results.
     */
    fun run(): DependencyUpdatesReporter {
      val projectConfigs =
        project.allprojects
          .associateBy({ it }, { it.configurations.matching(filterConfigurations).toCollection(LinkedHashSet()) })

      val status: List<PartialStatus> = resolveProjects(projectConfigs, checkConstraints)

      val buildscriptProjectConfigs =
        project.allprojects
          .associateBy({ it }, { it.buildscript.configurations.toCollection(LinkedHashSet()) })
      val buildscriptStatus: List<PartialStatus> =
        resolveProjects(
          buildscriptProjectConfigs,
          checkBuildEnvironmentConstraints,
        )

      return reporterFor(
        status + buildscriptStatus, project.path, project.logger, revision,
        outputFormatterArgument, project.file(outputDir), reportfileName, checkForGradleUpdate,
        gradleVersionsApiBaseUrl, gradleReleaseChannel,
      )
    }

    private fun resolveProjects(
      projectConfigs: Map<Project, Set<Configuration>>,
      checkConstraints: Boolean,
    ): List<PartialStatus> {
      val observed = mutableListOf<PartialStatus>()
      projectConfigs.forEach { (currentProject, currentConfigurations) ->
        val resolver = Resolver(currentProject, resolutionStrategy, checkConstraints)
        for (currentConfiguration in currentConfigurations) {
          if (currentConfiguration.isCanBeResolved) {
            for (newStatus in resolve(resolver, currentProject, currentConfiguration)) {
              observed.add(newStatus.toPartialStatus())
            }
          }
        }
      }
      return mergeStatuses(observed)
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

    companion object {
      /** Returns a reporter for the merged statuses of one or more projects. */
      @JvmStatic
      @Suppress("LongParameterList")
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
      ): DependencyUpdatesReporter {
        val versions = VersionMapping(logger, statuses)
        val unresolved = statuses.mapNotNullTo(mutableSetOf()) { it.unresolved }
        val projectUrls =
          statuses
            .filter { !it.projectUrl.isNullOrEmpty() }
            .associateBy(
              { mapOf("group" to it.group, "name" to it.name) },
              { it.projectUrl.toString() },
            )

        val currentVersions = toMap(versions.current)
        val latestVersions =
          versions.latest
            .associateBy({ mapOf("group" to it.groupId, "name" to it.artifactId) }, { it })
        val upToDateVersions = toMap(versions.upToDate)
        val downgradeVersions = toMap(versions.downgrade)
        val upgradeVersions = toMap(versions.upgrade)

        // Check for Gradle updates.
        val gradleUpdateChecker = GradleUpdateChecker(checkForGradleUpdate, gradleVersionsApiBaseUrl)

        return DependencyUpdatesReporter(
          projectPath, logger, revision, outputFormatterArgument, outputDir,
          reportfileName, currentVersions, latestVersions, upToDateVersions, downgradeVersions,
          upgradeVersions, versions.undeclared, unresolved, projectUrls, gradleUpdateChecker,
          gradleReleaseChannel, versions.latestByCurrent,
        )
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
