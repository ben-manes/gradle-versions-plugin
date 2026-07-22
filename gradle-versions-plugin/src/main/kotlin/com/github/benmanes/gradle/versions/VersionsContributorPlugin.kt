package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.registerProducer
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Contributes the project's dependency updates to the aggregate report without registering a
 * `dependencyUpdates` task of its own.
 *
 * Under isolated projects a project plugin cannot register a task in another project, so every
 * project must apply a plugin itself to be aggregated. Applying [VersionsPlugin] everywhere would
 * register a `dependencyUpdates` task in every project, and running `dependencyUpdates` by name
 * would then report per project instead of once from the root. Applying this plugin instead keeps
 * the root's task the only one with that name, so the report is invoked the same way as before.
 */
class VersionsContributorPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    requireMinimumGradleVersion("com.github.ben-manes.versions.contributor")
    registerProducer(project)
  }
}
