package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

open class BaseDependencyUpdatesTask : DefaultTask() {

  /** Returns the resolution revision level. */
  @Input
  var revision: String = "milestone"
    get() = (System.getProperties()["revision"] ?: field) as String

  /** Returns the resolution revision level. */
  @Input
  var gradleReleaseChannel: String = RELEASE_CANDIDATE.id
    get() = (System.getProperties()["gradleReleaseChannel"] ?: field) as String

  /** Returns the outputDir destination. */
  @Input
  var outputDir: String =
    "${project.buildDir.path.replace(project.projectDir.path + "/", "")}/dependencyUpdates"
    get() = (System.getProperties()["outputDir"] ?: field) as String

  /** Returns the filename of the report. */
  @Input
  @Optional
  var reportfileName: String = "report"
    get() = (System.getProperties()["reportfileName"] ?: field) as String

  // Groovy generates both get/is accessors for boolean properties unless we manually define some.
  // Gradle will reject this behavior starting in 7.0 so we make sure to define accessors ourselves.
  @Input
  var checkForGradleUpdate: Boolean = true

  @Input
  var checkConstraints: Boolean = false

  @Input
  var checkBuildEnvironmentConstraints: Boolean = false
}
