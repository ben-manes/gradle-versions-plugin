package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.publishSettingsClasspath
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

/**
 * Applies [VersionsPlugin] to the root project and [VersionsContributorPlugin] to every other
 * project of the build, but not of an included build, and reports the versions of the plugins
 * that the settings script declares.
 */
class VersionsSettingsPlugin : Plugin<Settings> {
  override fun apply(settings: Settings) {
    requireMinimumGradleVersion("io.github.ben-manes.versions.settings")
    if (!claims(settings)) {
      return
    }

    // The settings script's classpath carries the versions of the plugins that its own plugins
    // block declares, which no project's buildscript does.
    // https://github.com/ben-manes/gradle-versions-plugin/issues/367
    publishSettingsClasspath(settings.gradle, settings.buildscript.configurations.toList())

    // Isolated projects isolates the action of gradle.lifecycle.beforeProject so that the state it
    // captures cannot be shared between the projects it configures. This action captures nothing,
    // so the older hook is equivalent and does not require Gradle 8.8. Capturing the settings or a
    // field here would no longer be safe.
    settings.gradle.beforeProject { project ->
      // Only the root receives the reporting task, so that invoking dependencyUpdates by name runs
      // one task and writes one merged report rather than one per project.
      if (project.path == ":") {
        project.pluginManager.apply(VersionsPlugin::class.java)
      } else {
        project.pluginManager.apply(VersionsContributorPlugin::class.java)
      }
    }
  }
}
