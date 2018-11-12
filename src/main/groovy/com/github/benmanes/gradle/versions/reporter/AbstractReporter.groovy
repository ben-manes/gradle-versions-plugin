package com.github.benmanes.gradle.versions.reporter

import groovy.transform.TupleConstructor
import org.gradle.api.Project

/**
 * A base result object reporter for the dependency updates results.
 */
@TupleConstructor(includeFields = true)
abstract class AbstractReporter implements Reporter {
  /** The project evaluated against. */
  Project project
  /** The revision strategy evaluated with. */
  String revision
  /** The gradle release channel to use for reporting. */
  String gradleReleaseChannel
}
