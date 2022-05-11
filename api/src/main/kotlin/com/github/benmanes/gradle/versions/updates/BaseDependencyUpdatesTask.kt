package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import javax.annotation.Nullable

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

  @Internal
  var outputFormatter: Any = "plain"

  @Input
  @Optional
  fun getOutputFormatterName(): String? {
    return if (outputFormatter is String) {
      outputFormatter as String
    } else {
      null
    }
  }

  // Groovy generates both get/is accessors for boolean properties unless we manually define some.
  // Gradle will reject this behavior starting in 7.0 so we make sure to define accessors ourselves.
  @Input
  var checkForGradleUpdate: Boolean = true

  @Input
  var checkConstraints: Boolean = false

  @Input
  var checkBuildEnvironmentConstraints: Boolean = false

  @Internal
  @Nullable
  var resolutionStrategy: Closure<*>? = null

  @Nullable
  @Internal // TODO remove
  protected var resolutionStrategyAction: Action<in ResolutionStrategyWithCurrent>? = null

  /**
   * Sets the [resolutionStrategy] to the provided strategy.
   *
   * @param resolutionStrategy the resolution strategy
   */
  fun resolutionStrategy(resolutionStrategy: Action<in ResolutionStrategyWithCurrent>? = null) {
    this.resolutionStrategyAction = resolutionStrategy
    this.resolutionStrategy = null
  }

  /** Returns the outputDir format. */
  fun outputFormatter(): Any {
    return (System.getProperties()["outputFormatter"] ?: outputFormatter)
  }
}
