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
package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import groovy.transform.CompileStatic
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.util.GradleVersion

/**
 * Registers the plugin's tasks.
 */
@CompileStatic
class VersionsPlugin implements Plugin<Project> {
  @Override
  void apply(Project project) {
    if (GradleVersion.current() < GradleVersion.version("5.0")) {
      project.logger.error(
        "Gradle 5.0 or greater is required to apply the com.github.ben-manes.versions plugin.")
      return
    }

    project.tasks.register('dependencyUpdates', DependencyUpdatesTask)
  }
}
