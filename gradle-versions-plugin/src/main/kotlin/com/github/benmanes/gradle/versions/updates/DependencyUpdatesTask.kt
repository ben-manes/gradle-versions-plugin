package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentFilter
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.util.ConfigureUtil
import javax.annotation.Nullable

/**
 * A task that reports which dependencies have later versions.
 */
open class DependencyUpdatesTask : DefaultTask() { // tasks can't be final

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
  private var resolutionStrategyAction: Action<in ResolutionStrategyWithCurrent>? = null

  init {
    description = "Displays the dependency updates for the project."
    group = "Help"
    outputs.upToDateWhen { false }

    callIncompatibleWithConfigurationCache()
  }

  @TaskAction
  fun dependencyUpdates() {
    project.evaluationDependsOnChildren()
    if (resolutionStrategy != null) {
      resolutionStrategy(ConfigureUtil.configureUsing(resolutionStrategy))
      logger.warn(
        "dependencyUpdates.resolutionStrategy: " +
          "Remove the assignment operator, \"=\", when setting this task property"
      )
    }
    val evaluator = DependencyUpdates(
      project, resolutionStrategyAction, revision,
      outputFormatter(), outputDir, reportfileName, checkForGradleUpdate,
      gradleReleaseChannel, checkConstraints, checkBuildEnvironmentConstraints
    )
    val reporter = evaluator.run()
    reporter.write()
  }

  fun rejectVersionIf(filter: ComponentFilter) {
    resolutionStrategy { strategy ->
      strategy.componentSelection { selection ->
        selection.all(
          Action<ComponentSelectionWithCurrent> { current ->
            val isNotNull = current.currentVersion != null && current.candidate.version != null
            if (isNotNull && filter.reject(current)) {
              current.reject("Rejected by rejectVersionIf ")
            }
          }
        )
      }
    }
  }

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
  private fun outputFormatter(): Any {
    return (System.getProperties()["outputFormatter"] ?: outputFormatter)
  }

  private fun callIncompatibleWithConfigurationCache() {
    this::class.members.find { it.name == "notCompatibleWithConfigurationCache" }
      ?.call(this, "The gradle-versions-plugin isn't compatible with the configuration cache")
  }
}
