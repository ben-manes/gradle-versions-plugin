package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.util.GradleVersion

/**
 * Registers the plugin's tasks.
 */
class VersionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    if (GradleVersion.current() < GradleVersion.version("5.0")) {
      project.logger
        .error("Gradle 5.0 or greater is required to apply the com.github.ben-manes.versions plugin.")
      return
    }

    val tasks = project.tasks
    if (tasks.findByName("dependencyUpdates") == null) {
      tasks.register("dependencyUpdates", DependencyUpdatesTask::class.java)
    }
  }
}
