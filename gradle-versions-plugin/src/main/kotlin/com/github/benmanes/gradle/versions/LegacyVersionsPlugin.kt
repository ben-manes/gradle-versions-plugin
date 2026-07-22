package com.github.benmanes.gradle.versions

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The [VersionsPlugin] under its deprecated `com.github.ben-manes.versions` id.
 *
 * The Gradle Plugin Portal no longer accepts new plugin ids under a `com.github` group, so the
 * plugin moved to `io.github.ben-manes.versions`. The old id keeps receiving releases so that
 * existing builds continue to see updates. Applying it logs a deprecation warning and delegates
 * to [VersionsPlugin].
 */
class LegacyVersionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    requireMinimumGradleVersion("com.github.ben-manes.versions")
    project.logger.warn(
      "The com.github.ben-manes.versions plugin id is deprecated; apply io.github.ben-manes.versions instead.",
    )
    project.pluginManager.apply(VersionsPlugin::class.java)
  }
}
