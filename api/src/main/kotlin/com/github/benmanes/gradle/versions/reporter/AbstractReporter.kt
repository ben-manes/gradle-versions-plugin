package com.github.benmanes.gradle.versions.reporter

import org.gradle.api.Project
import java.io.OutputStream
import java.io.PrintStream

/**
 * A base result object reporter for the dependency updates results.
 *
 * @property project The project evaluated against.
 * @property revision The revision strategy evaluated with.
 * @property gradleReleaseChannel The gradle release channel to use for reporting.
 */
abstract class AbstractReporter(
  open val project: Project,
  open val revision: String,
  open val gradleReleaseChannel: String,
) : Reporter

fun OutputStream.print(s: String = "") {
  (this as PrintStream).print(s)
}

fun OutputStream.println(s: String = "") {
  (this as PrintStream).println(s)
}
