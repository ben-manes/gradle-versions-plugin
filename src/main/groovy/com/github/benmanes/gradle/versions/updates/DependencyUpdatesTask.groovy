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

/**
 * A task that reports which dependencies have later versions.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
@TypeChecked
class DependencyUpdatesTask extends DefaultTask {

  @Input
  String revision = 'milestone'

  @Input
  Object outputFormatter = 'plain'

  @Input
  String outputDir = 'build/dependencyUpdates'

  @Input
  Boolean useProjectAsFilename = false

  DependencyUpdatesTask() {
    description = 'Displays the dependency updates for the project.'
    group = 'Help'
  }

  @TaskAction
  def dependencyUpdates() {
    def evaluator = new DependencyUpdates(project, revisionLevel(), outputFormatterProp(), outputDirectory(),
      useProjectNameAsFilename())
    DependencyUpdatesReporter reporter = evaluator.run()
    reporter?.write()
  }

  /** Returns the resolution revision level. */
  String revisionLevel() { System.properties.get('revision', revision) }

  /** Returns the outputDir format. */
  Object outputFormatterProp() { System.properties.get('outputFormatter', outputFormatter) }

  /** Returns the outputDir destination. */
  String outputDirectory() { System.properties.get('outputDir', outputDir) }

  /** Returns the useProjectAsFilename boolean. */
  Boolean useProjectNameAsFilename() { System.properties.get('useProjectAsFilename', useProjectAsFilename) }
}
