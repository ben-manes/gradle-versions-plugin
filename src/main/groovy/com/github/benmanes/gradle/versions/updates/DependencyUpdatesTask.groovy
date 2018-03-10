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

import groovy.transform.TypeChecked
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Optional

/**
 * A task that reports which dependencies have later versions.
 */
@TypeChecked
class DependencyUpdatesTask extends DefaultTask {

  @Input
  String revision = 'milestone'

  @Input
  String outputDir =
    "${project.buildDir.path.replace(project.projectDir.path + '/', '')}/dependencyUpdates"

  @Input @Optional
  String getOutputFormatterName() {
    return (outputFormatter instanceof String) ? ((String) outputFormatter) : null
  }

  Object outputFormatter = 'plain';
  Closure resolutionStrategy = null;

  DependencyUpdatesTask() {
    description = 'Displays the dependency updates for the project.'
    group = 'Help'

    outputs.upToDateWhen { false }
  }

  @TaskAction
  def dependencyUpdates() {
    project.evaluationDependsOnChildren()

    def evaluator = new DependencyUpdates(project, resolutionStrategy,
      revisionLevel(), outputFormatterProp(), outputDirectory())
    DependencyUpdatesReporter reporter = evaluator.run()
    reporter?.write()
  }

  /** Returns the resolution revision level. */
  String revisionLevel() { System.properties['revision'] ?: revision }

  /** Returns the outputDir format. */
  Object outputFormatterProp() { System.properties['outputFormatter'] ?: outputFormatter }

  /** Returns the outputDir destination. */
  String outputDirectory() { System.properties['outputDir'] ?: outputDir }
}
