package com.github.benmanes.gradle.versions

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

/**
 * The [VersionsPlugin] under its earlier `io.github.ben-manes.versions.settings` id for a settings
 * script. Applying it applies [VersionsPlugin] to the settings.
 */
class VersionsSettingsPlugin : Plugin<Settings> {
  override fun apply(settings: Settings) {
    requireMinimumGradleVersion("io.github.ben-manes.versions.settings")
    settings.pluginManager.apply(VersionsPlugin::class.java)
  }
}
