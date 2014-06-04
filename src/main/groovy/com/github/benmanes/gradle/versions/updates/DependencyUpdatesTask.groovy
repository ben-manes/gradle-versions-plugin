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
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
/**
 * A task that reports which dependencies have later versions.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
class DependencyUpdatesTask extends DefaultTask {

  @Input
  String revision = 'milestone'

  @Input
  def outputFormatter = 'plain'

  @Input
  String outputDir = 'build/dependencyUpdates'

  DependencyUpdatesTask() {
    description = 'Displays the dependency updates for the project.'
    group = 'Help'
  }

  @TaskAction
  def dependencyUpdates() {
    def evaluator = new DependencyUpdates(project, revisionLevel(), outputFormatter(), outputDirectory())
    def reporter = evaluator.run()
    reporter.write()
  }

  /** Returns the resolution revision level. */
  def revisionLevel() { System.properties.get('revision', revision) }

  /** Returns the outputDir format. */
  def outputFormatter() { System.properties.get('outputFormatter', outputFormatter) }

  /** Returns the outputDir destination. */
  def outputDirectory() { System.properties.get('outputDir', outputDir) }
}
