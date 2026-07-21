package com.github.benmanes.gradle.versions

import static com.github.benmanes.gradle.versions.updates.DependencyUpdatesReporterKt.reporterFor
import static com.github.benmanes.gradle.versions.updates.PartialResultKt.mergeStatuses

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesReporter
import com.github.benmanes.gradle.versions.updates.OutputFormatterArgument
import com.github.benmanes.gradle.versions.updates.PartialStatus
import com.github.benmanes.gradle.versions.updates.Resolver
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

/**
 * Resolves each project the way its own producer task does and merges the statuses into a reporter,
 * so that a spec may exercise the resolution and reporting in process.
 */
final class ProjectEvaluator {

  @SuppressWarnings('ParameterCount')
  static DependencyUpdatesReporter evaluate(Project project,
      Action<ResolutionStrategyWithCurrent> resolutionStrategy, String revision,
      OutputFormatterArgument outputFormatter, String outputDir, String reportfileName,
      boolean checkForGradleUpdate, String gradleVersionsApiBaseUrl, String gradleReleaseChannel,
      boolean checkConstraints = false, boolean checkBuildEnvironmentConstraints = false,
      Closure<Boolean> configurationFilter = { true }) {
    def statuses =
      mergeStatuses(project.allprojects.collectMany { Project current ->
        ProjectEvaluator.statusesOf(current, current.configurations.findAll(configurationFilter),
          resolutionStrategy, revision, checkConstraints)
      }) +
      mergeStatuses(project.allprojects.collectMany { Project current ->
        ProjectEvaluator.statusesOf(current, current.buildscript.configurations, resolutionStrategy,
          revision, checkBuildEnvironmentConstraints)
      })

    return reporterFor(statuses, project.path, project.logger, revision, outputFormatter,
      project.file(outputDir), reportfileName, checkForGradleUpdate, gradleVersionsApiBaseUrl,
      gradleReleaseChannel)
  }

  protected static List<PartialStatus> statusesOf(Project project,
      Collection<Configuration> configurations,
      Action<ResolutionStrategyWithCurrent> resolutionStrategy, String revision,
      boolean checkConstraints) {
    def resolver = new Resolver(project, resolutionStrategy, checkConstraints)
    return configurations.findAll { it.canBeResolved }.collectMany { Configuration configuration ->
      try {
        return resolver.resolve(configuration, revision).collect { it.toPartialStatus() }
      } catch (Exception e) {
        project.logger.info("Skipping configuration ${project.path}:${configuration.name}", e)
        return []
      }
    }
  }
}
