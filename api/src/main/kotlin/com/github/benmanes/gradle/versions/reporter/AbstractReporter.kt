package com.github.benmanes.gradle.versions.reporter

import org.gradle.api.Project

/**
 * A base result object reporter for the dependency updates results.
 */
abstract class AbstractReporter @JvmOverloads constructor(
  /** The project evaluated against. */
  val project: Project,
  /** The revision strategy evaluated with. */
  val revision: String,
  /** The gradle release channel to use for reporting. */
  val gradleReleaseChannel: String,
) : Reporter
