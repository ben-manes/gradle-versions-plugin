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

import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentFilter
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionRulesWithCurrent
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.transform.TypeChecked
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.util.ConfigureUtil
import org.gradle.util.SingleMessageLogger;

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.*

/**
 * A task that reports which dependencies have later versions.
 */
@TypeChecked
class DependencyUpdatesTask extends DefaultTask {

  @Input
  String revision = 'milestone'

  @Input
  String gradleReleaseChannel = RELEASE_CANDIDATE.id

  @Input
  String outputDir =
    "${project.buildDir.path.replace(project.projectDir.path + '/', '')}/dependencyUpdates"

  @Input @Optional
  String reportfileName = 'report'

  @Input @Optional
  String getOutputFormatterName() {
    return (outputFormatter instanceof String) ? ((String) outputFormatter) : null
  }

  @Input
  boolean checkForGradleUpdate = true

  @Input
  boolean checkConstraints = false

  Object outputFormatter = 'plain'

  Closure resolutionStrategy = null;
  private Action<? super ResolutionStrategyWithCurrent> resolutionStrategyAction = null

  DependencyUpdatesTask() {
    description = 'Displays the dependency updates for the project.'
    group = 'Help'

    outputs.upToDateWhen { false }
  }

  @TaskAction
  def dependencyUpdates() {
    project.evaluationDependsOnChildren()

    if (resolutionStrategy != null) {
      resolutionStrategy(ConfigureUtil.configureUsing(resolutionStrategy))
      SingleMessageLogger.nagUserWith('dependencyUpdates.resolutionStrategy', /* removalDetails */ '',
        'Remove the assignment operator, \'=\', when setting this task property', /* contextualAdvice */ null);
    }

    def evaluator = new DependencyUpdates(project, resolutionStrategyAction, revisionLevel(),
      outputFormatterProp(), outputDirectory(), getReportfileName(), checkForGradleUpdate, gradleReleaseChannelLevel(),
      checkConstraints)
    DependencyUpdatesReporter reporter = evaluator.run()
    reporter?.write()
  }

  /**
   * Sets the {@link #resolutionStrategy} to the provided strategy.
   * @param resolutionStrategy the resolution strategy
   */
  void resolutionStrategy(final Action<? super ResolutionStrategyWithCurrent> resolutionStrategy) {
    this.resolutionStrategyAction = resolutionStrategy
    this.resolutionStrategy = null
  }

  void rejectVersionIf(final ComponentFilter filter) {
    resolutionStrategy { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { ComponentSelectionRulesWithCurrent selection ->
        selection.all { ComponentSelectionWithCurrent current ->
          def isNotNull = current.currentVersion != null && current.candidate.version != null
          if (isNotNull && filter.reject(current)) {
            current.reject("Rejected by rejectVersionIf ")
          }
        }
      }
    }
  }

  /** Returns the resolution revision level. */
  String revisionLevel() { System.properties['revision'] ?: revision }

  /** Returns the resolution revision level. */
  String gradleReleaseChannelLevel() { System.properties['gradleReleaseChannel'] ?: gradleReleaseChannel }

  /** Returns the outputDir format. */
  Object outputFormatterProp() { System.properties['outputFormatter'] ?: outputFormatter }

  /** Returns the outputDir destination. */
  String outputDirectory() { System.properties['outputDir'] ?: outputDir }

  /** Returns the filename of the report. */
  String getReportfileName() { System.properties.get('reportfileName', reportfileName) }
}
