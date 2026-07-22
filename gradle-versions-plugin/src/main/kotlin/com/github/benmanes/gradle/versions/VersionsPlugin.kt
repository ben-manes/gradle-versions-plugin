package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.github.benmanes.gradle.versions.updates.registerAggregation
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.util.GradleVersion
import org.xml.sax.SAXException
import javax.xml.parsers.SAXParserFactory

/**
 * Registers the plugin's tasks.
 */
class VersionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    requireMinimumGradleVersion("com.github.ben-manes.versions")

    val tasks = project.tasks
    if (!tasks.names.contains("dependencyUpdates")) {
      val task =
        tasks.register("dependencyUpdates", DependencyUpdatesTask::class.java) { task ->
          task.doFirst(
            object : Action<Task> {
              override fun execute(t: Task) {
                requireSupportedSaxParser()
              }
            },
          )
        }
      registerAggregation(project, task)
    }
  }

  private fun requireSupportedSaxParser() {
    if (GradleVersion.current() <= GradleVersion.version("8.10.2")) {
      try {
        val factory = SAXParserFactory.newInstance()
        factory.newSAXParser().setProperty("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
      } catch (ex: SAXException) {
        throw GradleException(
          """A plugin or custom build logic has included an insecure XML parser, which is not compatible for
            |dependency resolution with this version of Gradle. You can work around this issue by specifying
            |the SAXParserFactory to use or by updating any plugin that depends on an old XML parser version.
            |
            |Use ./gradlew buildEnvironment to check your build's plugin dependencies.
            |
            |For more details and a workaround see,
            |https://docs.gradle.org/8.4/userguide/upgrading_version_8.html#changes_8.4
            |
          """.trimMargin(),
        )
      }
    }
  }
}

internal fun requireMinimumGradleVersion(pluginId: String) {
  if (GradleVersion.current() < GradleVersion.version("8.4")) {
    throw GradleException("Gradle 8.4 or greater is required to apply the $pluginId plugin.")
  }
}
