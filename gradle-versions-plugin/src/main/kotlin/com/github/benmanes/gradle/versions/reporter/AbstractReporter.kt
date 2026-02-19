package com.github.benmanes.gradle.versions.reporter

import java.io.OutputStream
import java.io.PrintStream

/**
 * A base result object reporter for the dependency updates results.
 *
 * @property projectPath The project path for display purposes.
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
