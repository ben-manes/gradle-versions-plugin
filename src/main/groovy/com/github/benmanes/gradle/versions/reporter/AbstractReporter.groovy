package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.*
import groovy.transform.TupleConstructor

/**
 * A base result object reporter for the dependency updates results.
 *
 * @author Zenedith (zenedith@wp.pl)
 */
@TupleConstructor(includeFields = true)
abstract class AbstractReporter implements Reporter {
  /** The project evaluated against. */
  def project
  /** The revision strategy evaluated with. */
  def revision

}
