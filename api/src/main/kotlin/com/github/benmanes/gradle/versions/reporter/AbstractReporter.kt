package com.github.benmanes.gradle.versions.reporter

import org.gradle.api.Project
import java.io.OutputStream
import java.io.PrintStream

/**
 * A base result object reporter for the dependency updates results.
 */
abstract class AbstractReporter @JvmOverloads constructor(
  /** The project evaluated against. */
  open val project: Project,
  /** The revision strategy evaluated with. */
  open val revision: String,
  /** The gradle release channel to use for reporting. */
  open val gradleReleaseChannel: String,
) : Reporter

fun OutputStream.print(x: String = "") {
  (this as PrintStream).print(x)
}

fun OutputStream.println(x: String = "") {
  (this as PrintStream).println(x)
}
