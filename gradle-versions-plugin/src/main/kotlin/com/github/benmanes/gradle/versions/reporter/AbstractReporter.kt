package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.Dependency
import java.io.OutputStream
import java.io.PrintStream

/**
 * A base result object reporter for the dependency updates results.
 *
 * @property projectPath The build tree path of the project evaluated against.
 * @property revision The revision strategy evaluated with.
 * @property gradleReleaseChannel The gradle release channel to use for reporting.
 */
abstract class AbstractReporter(
  open val projectPath: String,
  open val revision: String,
  open val gradleReleaseChannel: String,
) : Reporter

fun OutputStream.print(s: String = "") {
  (this as PrintStream).print(s)
}

fun OutputStream.println(s: String = "") {
  (this as PrintStream).println(s)
}

/** The number of names listed before a long list is elided in the human readable reports. */
private const val MAX_LISTED_NAMES = 5

/** Returns the display list of names, eliding all but the first few of a long list. */
private fun elidedLabel(names: List<String>): String =
  if (names.size <= MAX_LISTED_NAMES + 1) {
    names.joinToString(", ")
  } else {
    names.take(MAX_LISTED_NAMES).joinToString(", ") + " and ${names.size - MAX_LISTED_NAMES} others"
  }

/**
 * Returns the display list of the projects that declared a divergent version, eliding all but the
 * first few of a long list.
 */
internal fun projectsLabel(projects: List<String>): String = elidedLabel(projects.map { if (it == ":") "root project" else it })

/** Returns the display list of the configurations a dependency was declared against. */
private fun configurationsLabel(names: List<String>): String {
  val quoted = names.map { "'$it'" }
  val listed =
    if (quoted.size <= MAX_LISTED_NAMES + 1) {
      if (quoted.size == 1) {
        quoted.single()
      } else {
        quoted.dropLast(1).joinToString(", ") + " and ${quoted.last()}"
      }
    } else {
      quoted.take(MAX_LISTED_NAMES).joinToString(", ") + " and ${quoted.size - MAX_LISTED_NAMES} more"
    }
  val noun = if (names.size == 1) "configuration" else "configurations"
  return "the $listed $noun"
}

/**
 * Returns the line describing where the dependency's version came from, or null for a declared one.
 *
 * A row is handed at most one attribution, ranked by how directly the build can edit the version
 * reported: a declaration outranks the platform mark, which outranks the plugin's. Which of the
 * first two a row carries is decided where the statuses are assembled, so the branch order below
 * settles only what the mark displaces, which is the configurations a declaration named.
 */
internal fun sourceLabel(dependency: Dependency): String? {
  val projects = dependency.projects
  val platforms = dependency.platformProjects?.takeIf { it.isNotEmpty() }
  if (platforms != null) {
    val noun = if (platforms.size == 1) "platform" else "platforms"
    val imported = "imported by the $noun ${projectsLabel(platforms)}"
    return if (projects == null) imported else "$imported in ${projectsLabel(projects)}"
  }
  val names = dependency.configurations?.takeIf { it.isNotEmpty() }
  if (dependency.contributed != true) {
    // A configuration is named only when the dependency was declared directly against a resolvable
    // one, which is how a plugin adding its own classpath eagerly leaves it.
    val where = names?.let { "declared in ${configurationsLabel(it)}" }
    return when {
      where == null -> projects?.let { "declared in ${projectsLabel(it)}" }
      projects == null -> where
      else -> "$where in ${projectsLabel(projects)}"
    }
  }
  val into = names?.let { " into ${configurationsLabel(it)}" }.orEmpty()
  return if (projects == null) {
    "contributed by a plugin$into"
  } else {
    "contributed by a plugin$into in ${projectsLabel(projects)}"
  }
}
