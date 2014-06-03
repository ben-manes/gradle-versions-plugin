package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.Result;

import groovy.json.JsonBuilder
import groovy.transform.TupleConstructor

/**
 * A json reporter for the dependency updates results.
 *
 * @author Zenedith (zenedith@wp.pl)
 */
@TupleConstructor(callSuper = true, includeSuperProperties = true, includeSuperFields = true)
class JsonReporter extends AbstractReporter {

  def write(printStream, Result result) {
    printStream.println new JsonBuilder(result).toPrettyString().stripMargin()
  }

  @Override
  def getFileName() {
    return 'report.json'
  }
}
