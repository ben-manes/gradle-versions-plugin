package com.github.benmanes.gradle.versions.updates

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.logging.Logging

private const val KOTLIN_GROUP = "org.jetbrains.kotlin"
private const val KOTLIN_DSL_GROUP = "org.gradle.kotlin"
private const val EMBEDDED_KOTLIN_CONFIGURATION = "embeddedKotlin"

/** Declared by `kotlin-dsl` for the plugins blocks of precompiled script plugins. */
private const val PLUGINS_BLOCKS_CONFIGURATION = "compilePluginsBlocksPluginClasspath"

/** Declared by `kotlin-dsl` and by the Kotlin Gradle Plugin, outside [EMBEDDED_KOTLIN_CONFIGURATION]. */
private const val SCRIPTING_COMPILER = "kotlin-scripting-compiler-embeddable"

/** The Kotlin Gradle Plugin's compiler plugin classpaths, one per source set, which declare [SCRIPTING_COMPILER]. */
private const val COMPILER_PLUGIN_CLASSPATHS = "kotlinCompilerPluginClasspath"

private val logger = Logging.getLogger(EmbeddedKotlin::class.java)

/** The Kotlin version embedded in the Gradle running the build, read once for the build. */
private val embeddedKotlinVersion: String? by lazy {
  gradleKotlinDslValue("org.gradle.kotlin.dsl.KotlinDependencyExtensionsKt", "getEmbeddedKotlinVersion")
}

/**
 * The version of the `kotlin-dsl` plugins paired with the Gradle running the build, read once for the
 * build. Gradle warns when any other version is applied, and exposes this one only outside its public
 * API.
 */
private val kotlinDslPluginsVersion: String? by lazy {
  gradleKotlinDslValue("org.gradle.kotlin.dsl.support.KotlinDslPluginsKt", "getExpectedKotlinDslPluginsVersion")
}

/**
 * Reads a top-level property of Gradle's Kotlin DSL, which the plugin cannot compile against as the
 * Gradle API it builds with leaves that jar out. Null where the Gradle running the build does not
 * expose it, and then the entries it would match are reported.
 */
private fun gradleKotlinDslValue(
  className: String,
  getter: String,
): String? =
  try {
    Class.forName(className).getMethod(getter).invoke(null) as? String
  } catch (e: ReflectiveOperationException) {
    logUnavailable(className, getter, e)
  } catch (e: LinkageError) {
    logUnavailable(className, getter, e)
  }

private fun logUnavailable(
  className: String,
  getter: String,
  e: Throwable,
): String? {
  logger.info("Reporting the versions Gradle sets for its embedded Kotlin, as $className.$getter is unavailable", e)
  return null
}

/** The versions Gradle sets for its embedded Kotlin in one project, which only a Gradle upgrade changes. */
internal class EmbeddedKotlin(
  private val kotlinVersion: String?,
  private val modules: Set<String>,
  private val kotlinDslVersion: String?,
) {
  /**
   * Returns whether the status is a module added by Gradle's Kotlin plugins at the embedded Kotlin
   * version on a project classpath, or, on a script classpath, one of the `kotlin-dsl` plugins at the
   * version paired with the running Gradle. Nothing Gradle adds to a script classpath is one of those
   * modules, so a module there is the build's own. A later release of those plugins is published for
   * a later Gradle, so on a script classpath the paired version is matched whether or not the project
   * applies the plugin. Declared as a library, they are compiled into what the build publishes, and
   * are reported.
   */
  fun pins(
    status: PartialStatus,
    scriptClasspath: Boolean,
  ): Boolean =
    status.unresolved == null &&
      when {
        !scriptClasspath && status.group == KOTLIN_GROUP ->
          status.name in modules && status.declaredVersion == kotlinVersion
        scriptClasspath && isKotlinDslPlugin(status) -> status.declaredVersion == kotlinDslVersion
        else -> false
      }

  /** Returns whether the status is a marker of a plugin in `gradle-kotlin-dsl-plugins`, or that artifact. */
  private fun isKotlinDslPlugin(status: PartialStatus): Boolean =
    (status.group == KOTLIN_DSL_GROUP && status.name == "gradle-kotlin-dsl-plugins") ||
      (status.group.startsWith("$KOTLIN_DSL_GROUP.") && status.name == "${status.group}.gradle.plugin")

  companion object {
    /** Returns the versions Gradle sets in the project, read after the project's plugins are applied. */
    fun of(project: Project): EmbeddedKotlin {
      val kotlinVersion =
        embeddedKotlinVersion.takeIf { project.pluginManager.hasPlugin("org.gradle.kotlin.embedded-kotlin") }
      val modules = if (kotlinVersion == null) emptySet() else modulesDeclaredByGradle(project, kotlinVersion)
      return EmbeddedKotlin(kotlinVersion, modules, kotlinDslPluginsVersion)
    }

    /**
     * Returns the modules in [EMBEDDED_KOTLIN_CONFIGURATION], and the scripting compiler, less any
     * also declared or constrained at the embedded version in another configuration of the project.
     * [PLUGINS_BLOCKS_CONFIGURATION] and [COMPILER_PLUGIN_CLASSPATHS] are not counted as another.
     */
    private fun modulesDeclaredByGradle(
      project: Project,
      kotlinVersion: String,
    ): Set<String> {
      val (gradle, build) =
        project.configurations.toList().partition {
          it.name == EMBEDDED_KOTLIN_CONFIGURATION || it.name == PLUGINS_BLOCKS_CONFIGURATION ||
            it.name.startsWith(COMPILER_PLUGIN_CLASSPATHS)
        }
      val declaredByGradle =
        gradle.filter { it.name == EMBEDDED_KOTLIN_CONFIGURATION }.kotlinModules().map { it.name } + SCRIPTING_COMPILER
      val declaredByBuild =
        build.kotlinModules().filter { it.version == kotlinVersion }.map { it.name } +
          build
            .flatMap { it.dependencyConstraints }
            .filter { it.group == KOTLIN_GROUP && it.version == kotlinVersion }
            .map { it.name }
      return declaredByGradle.toSet() - declaredByBuild.toSet()
    }

    /** Returns the Kotlin modules declared directly in these configurations. */
    private fun List<Configuration>.kotlinModules(): List<ExternalModuleDependency> =
      flatMap { it.dependencies.withType(ExternalModuleDependency::class.java) }.filter { it.group == KOTLIN_GROUP }
  }
}
