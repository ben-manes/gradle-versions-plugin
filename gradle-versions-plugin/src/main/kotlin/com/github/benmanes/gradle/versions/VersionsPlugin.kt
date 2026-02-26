package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.github.benmanes.gradle.versions.updates.WhenReadyAction
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.util.GradleVersion
import org.xml.sax.SAXException
import javax.xml.parsers.SAXParserFactory

/**
 * Registers the plugin's tasks.
 */
class VersionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    requireMinimumGradleVersion()
    requireSupportedSaxParser()

    val tasks = project.tasks
    if (!tasks.names.contains("dependencyUpdates")) {
      tasks.register("dependencyUpdates", DependencyUpdatesTask::class.java)
    }

    // Set common properties for ALL tasks of this type (including user-created ones).
    // buildDirRelative is computed inside configureEach so it respects any later changes
    // to project.layout.buildDirectory made by the build script.
    tasks.withType(DependencyUpdatesTask::class.java).configureEach { task ->
      val buildDirRelative =
        project.layout.buildDirectory.get().asFile.relativeTo(project.projectDir).path
      task.outputDir = "$buildDirRelative/dependencyUpdates"
      task.taskProjectDir = project.projectDir
      task.taskProjectPath = project.path
      task.isParallelExecution = project.gradle.startParameter.isParallelProjectExecutionEnabled
    }

    // Register the whenReady callback here (during project evaluation) rather than
    // inside the task configuration action. This ensures the callback fires after ALL
    // configuration actions (including configureEach from build scripts) have run.
    WhenReadyAction.register(project)
  }

  private fun requireMinimumGradleVersion() {
    if (GradleVersion.current() < GradleVersion.version("5.0")) {
      throw GradleException("Gradle 5.0 or greater is required to apply the com.github.ben-manes.versions plugin.")
    }
  }

  private fun requireSupportedSaxParser() {
    val isRestrictedInPatch =
      GradleVersion.current() >= GradleVersion.version("7.6.3") &&
        GradleVersion.current() <= GradleVersion.version("8.0")
    val isRestrictedInMajor = GradleVersion.current() >= GradleVersion.version("8.4")

    if (isRestrictedInPatch || isRestrictedInMajor) {
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
