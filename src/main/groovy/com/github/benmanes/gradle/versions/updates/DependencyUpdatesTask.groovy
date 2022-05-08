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

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE

import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentFilter
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionRulesWithCurrent
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.transform.CompileStatic
import javax.annotation.Nullable
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.util.ConfigureUtil

/**
 * A task that reports which dependencies have later versions.
 */
@CompileStatic
class DependencyUpdatesTask extends DefaultTask {

  @Input
  String revision = "milestone"

  @Input
  String gradleReleaseChannel = RELEASE_CANDIDATE.id

  @Input
  String outputDir =
    "${project.buildDir.path.replace(project.projectDir.path + "/", "")}/dependencyUpdates"

  @Input
  @Optional
  String reportfileName = "report"

  @Input
  @Optional
  @Nullable
  String getOutputFormatterName() {
    return (outputFormatter instanceof String) ? ((String) outputFormatter) : null
  }

  // Groovy generates both get/is accessors for boolean properties unless we manually define some.
  // Gradle will reject this behavior starting in 7.0 so we make sure to define accessors ourselves.
  boolean checkForGradleUpdate = true
  boolean checkConstraints = false
  boolean checkBuildEnvironmentConstraints = false

  @Input
  boolean getCheckForGradleUpdate() {
    return checkForGradleUpdate
  }

  @Input
  boolean getCheckConstraints() {
    return checkConstraints
  }

  @Input
  boolean getCheckBuildEnvironmentConstraints() {
    return checkBuildEnvironmentConstraints
  }

  @Internal
  Object outputFormatter = "plain"

  @Internal
  @Nullable
  Closure<?> resolutionStrategy = null

  @Nullable
  private Action<? super ResolutionStrategyWithCurrent> resolutionStrategyAction = null

  DependencyUpdatesTask() {
    description = "Displays the dependency updates for the project."
    group = "Help"

    outputs.upToDateWhen { false }

    if (supportsIncompatibleWithConfigurationCache()) {
      callIncompatibleWithConfigurationCache()
    }
  }

  private boolean supportsIncompatibleWithConfigurationCache() {
    return metaClass.respondsTo(this, "notCompatibleWithConfigurationCache", String)
  }

  private void callIncompatibleWithConfigurationCache() {
    String methodName = "notCompatibleWithConfigurationCache"
    Object[] methodArgs = ["The gradle-versions-plugin isn't compatible with the configuration cache"]
    metaClass.invokeMethod(this, methodName, methodArgs)
  }

  @TaskAction
  void dependencyUpdates() {
    project.evaluationDependsOnChildren()

    if (resolutionStrategy != null) {
      resolutionStrategy(ConfigureUtil.configureUsing(resolutionStrategy))
      logger.warn("dependencyUpdates.resolutionStrategy: " +
        "Remove the assignment operator, \"=\", when setting this task property")
    }

    DependencyUpdates evaluator = new DependencyUpdates(project, resolutionStrategyAction,
      revisionLevel(),
      outputFormatterProp(), outputDirectory(), getReportfileName(), checkForGradleUpdate,
      gradleReleaseChannelLevel(),
      checkConstraints, checkBuildEnvironmentConstraints)
    DependencyUpdatesReporter reporter = evaluator.run()
    reporter.write()
  }

  /**
   * Sets the {@link #resolutionStrategy} to the provided strategy.
   * @param resolutionStrategy the resolution strategy
   */
  void resolutionStrategy(Action<? super ResolutionStrategyWithCurrent> resolutionStrategy) {
    this.resolutionStrategyAction = resolutionStrategy
    this.resolutionStrategy = null
  }

  void rejectVersionIf(ComponentFilter filter) {
    resolutionStrategy { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { ComponentSelectionRulesWithCurrent selection ->
        selection.all { ComponentSelectionWithCurrent current ->
          boolean isNotNull = current.currentVersion != null && current.candidate.version != null
          if (isNotNull && filter.reject(current)) {
            current.reject("Rejected by rejectVersionIf ")
          }
        }
      }
    }
  }

  /** Returns the resolution revision level. */
  String revisionLevel() {
    return System.properties["revision"] ?: revision
  }

  /** Returns the resolution revision level. */
  String gradleReleaseChannelLevel() {
    return System.properties["gradleReleaseChannel"] ?: gradleReleaseChannel
  }

  /** Returns the outputDir format. */
  Object outputFormatterProp() {
    return System.properties["outputFormatter"] ?: outputFormatter
  }

  /** Returns the outputDir destination. */
  String outputDirectory() {
    return System.properties["outputDir"] ?: outputDir
  }

  /** Returns the filename of the report. */
  String getReportfileName() {
    return System.properties.get("reportfileName", reportfileName)
  }
}
