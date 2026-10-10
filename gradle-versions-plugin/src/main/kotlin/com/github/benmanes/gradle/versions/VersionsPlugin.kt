package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.github.benmanes.gradle.versions.updates.publishSettingsClasspath
import com.github.benmanes.gradle.versions.updates.registerAggregation
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.PluginAware
import org.gradle.util.GradleVersion
import org.xml.sax.SAXException
import javax.xml.parsers.SAXParserFactory

/**
 * Registers the plugin's tasks when applied to a project. When applied to a settings script, or to
 * an init script once the settings script has been evaluated, applies
 * itself to the root project and [VersionsContributorPlugin] to every other project of the build,
 * but not of an included build, and reports the versions of the plugins declared in the settings
 * script.
 */
class VersionsPlugin : Plugin<PluginAware> {
  override fun apply(target: PluginAware) {
    requireMinimumGradleVersion("io.github.ben-manes.versions")
    when (target) {
      is Project -> applyTo(target)
      is Settings -> applyTo(target)
      // An init script's classpath is separate from the build's, so the plugin is applied to the
      // settings only once the settings script has had the chance to apply the build's copy. Before
      // v0.66.0 that copy is registered under the settings plugin's id only, and the one in v0.56.0
      // does not guard against a second copy.
      is Gradle ->
        target.settingsEvaluated { settings ->
          val plugins = settings.pluginManager
          if (!plugins.hasPlugin("io.github.ben-manes.versions") &&
            !plugins.hasPlugin("io.github.ben-manes.versions.settings")
          ) {
            plugins.apply(VersionsPlugin::class.java)
          }
        }
      else -> throw GradleException(
        "The io.github.ben-manes.versions plugin cannot be applied to ${target.javaClass.name}.",
      )
    }
  }

  private fun applyTo(settings: Settings) {
    if (!claims(settings)) {
      return
    }

    // The settings script's classpath contains the versions of the plugins that its own plugins
    // block declares, which appear in no project's buildscript.
    // https://github.com/ben-manes/gradle-versions-plugin/issues/367
    publishSettingsClasspath(settings.gradle, settings.buildscript.configurations)

    // Isolated projects isolates the action of gradle.lifecycle.beforeProject so that the state it
    // captures cannot be shared between the projects it configures. This action captures nothing,
    // so the older hook is equivalent and does not require Gradle 8.8. Capturing the settings or a
    // field here would no longer be safe.
    settings.gradle.beforeProject { project ->
      // The reporting task is registered only in the root, so that invoking dependencyUpdates by
      // name runs one task and writes one merged report rather than one per project.
      if (project.path == ":") {
        project.pluginManager.apply(VersionsPlugin::class.java)
      } else {
        project.pluginManager.apply(VersionsContributorPlugin::class.java)
      }
    }
  }

  private fun applyTo(project: Project) {
    if (!claims(project)) {
      return
    }

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

/**
 * Returns whether this copy of the plugin may configure [owner], which it may not if a copy loaded
 * by another classloader already has.
 *
 * An init script injects the plugin from a classpath of its own, so a build that applies the plugin
 * as well ends up with two copies of every class. Gradle keys the tasks, configurations, and shared
 * services that the plugin registers by name, and the second copy would fail to cast what the first
 * registered to its own type. The first copy configures the build and the rest do nothing, which
 * keeps a build that applies the plugin working when an init script injects it too. The marker is
 * stored under a plain string key and compared by reference, which every copy can do without
 * loading a type that belongs to another.
 */
internal fun claims(owner: ExtensionAware): Boolean {
  val identity = VersionsPlugin::class.java.classLoader
  val properties = owner.extensions.extraProperties
  if (!properties.has(OWNER_PROPERTY)) {
    properties.set(OWNER_PROPERTY, identity)
    return true
  }
  if (properties.get(OWNER_PROPERTY) === identity) {
    return true
  }
  Logging.getLogger(VersionsPlugin::class.java).info(
    "Another copy of the gradle-versions-plugin already configures {}, so this one is ignored.",
    owner,
  )
  return false
}

private const val OWNER_PROPERTY = "com.github.benmanes.gradle.versions.owner"
