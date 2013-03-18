/*
 * Copyright 2013 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.gradle.versions.analyze

import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalysis
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * A task that analyzes the dependencies to determine which are:
 * used and declared; used and undeclared; unused and declared.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 * @author Kelly Robinson (krobinson@sonatype.com)
 */
class DependencyAnalyzeTask extends DefaultTask {

  DependencyAnalyzeTask() {
    description = 'Displays the dependencies that are used and unused.'
    group = 'Help'
  }

  @TaskAction
  public def analyze() {
    def analyzer = new ProjectDependencyAnalyzer()

    project.allprojects.findAll { proj ->
      proj.plugins.hasPlugin('java')
    }.collectMany{ proj ->
      ProjectDependencyAnalysis analysis = analyzer.analyzeDependencies(project)
      def reporter = new DependencyAnalyzeReporter(project, analysis)
      reporter.writeToConsole()
    }
  }
}
