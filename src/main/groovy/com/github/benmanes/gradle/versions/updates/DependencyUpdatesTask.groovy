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

import static org.codehaus.groovy.runtime.DefaultGroovyMethods.asBoolean
import static org.codehaus.groovy.runtime.DefaultGroovyMethods.getMetaClass

import groovy.transform.CompileStatic
import org.gradle.api.tasks.TaskAction
import org.gradle.util.ConfigureUtil

/**
 * A task that reports which dependencies have later versions.
 */
@CompileStatic
class DependencyUpdatesTask extends BaseDependencyUpdatesTask {

  DependencyUpdatesTask() {
    description = "Displays the dependency updates for the project."
    group = "Help"

    outputs.upToDateWhen { false }

    if (supportsIncompatibleWithConfigurationCache()) {
      callIncompatibleWithConfigurationCache()
    }
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
      revision,
      outputFormatter(), outputDir, reportfileName, checkForGradleUpdate,
      gradleReleaseChannel,
      checkConstraints, checkBuildEnvironmentConstraints)
    DependencyUpdatesReporter reporter = evaluator.run()
    reporter.write()
  }

  private boolean supportsIncompatibleWithConfigurationCache() {
    return asBoolean(
      getMetaClass(this).respondsTo(this, "notCompatibleWithConfigurationCache", String))
  }

  private void callIncompatibleWithConfigurationCache() {
    String methodName = "notCompatibleWithConfigurationCache"
    Object[] methodArgs = ["The gradle-versions-plugin isn't compatible with the configuration cache"]
    getMetaClass(this).invokeMethod(this, methodName, methodArgs)
  }
}
