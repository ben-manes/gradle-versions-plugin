package com.github.benmanes.gradle.versions.reporter

import java.io.OutputStream
import java.io.PrintStream

/**
 * A base result object reporter for the dependency updates results.
 *
 * @property projectPath The path of the project evaluated against.
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

/** The number of projects named before a long list is elided in the human readable reports. */
private const val MAX_LISTED_PROJECTS = 5

/**
 * Returns the display list of the projects that declared a divergent version, eliding all but the
 * first few of a long list.
 */
internal fun projectsLabel(projects: List<String>): String {
  val names = projects.map { if (it == ":") "root project" else it }
  return if (names.size <= MAX_LISTED_PROJECTS + 1) {
    names.joinToString(", ")
  } else {
    names.take(MAX_LISTED_PROJECTS).joinToString(", ") + " and ${names.size - MAX_LISTED_PROJECTS} others"
  }
}
