package com.github.benmanes.gradle.versions.reporter

import org.gradle.api.Project
import java.io.OutputStream
import java.io.PrintStream

/**
 * A base result object reporter for the dependency updates results.
 */
abstract class AbstractReporter(
  /** The project evaluated against. */
  open val project: Project,
  /** The revision strategy evaluated with. */
  open val revision: String,
  /** The gradle release channel to use for reporting. */
  open val gradleReleaseChannel: String,
) : Reporter

fun OutputStream.print(s: String = "") {
  (this as PrintStream).print(s)
}

fun OutputStream.println(s: String = "") {
  (this as PrintStream).println(s)
}
