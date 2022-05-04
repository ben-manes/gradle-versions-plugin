package com.github.benmanes.gradle.versions.reporter

import org.gradle.api.Project
import java.io.PrintWriter

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

fun Appendable.println(x: String) {
  (this as PrintWriter).println(x)
}
