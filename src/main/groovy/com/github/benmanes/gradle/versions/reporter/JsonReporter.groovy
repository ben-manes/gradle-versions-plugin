package com.github.benmanes.gradle.versions.reporter

import groovy.json.JsonBuilder
import groovy.transform.TupleConstructor

/**
 * A json reporter for the dependency updates results.
 *
 * @author Zenedith (zenedith@wp.pl)
 */
@TupleConstructor(callSuper = true, includeSuperProperties = true, includeSuperFields = true)
class JsonReporter extends ObjectReporter implements Reporter {

  def write(printStream) {
    def responseObject = buildBaseObject()
    printStream.println new JsonBuilder(responseObject).toPrettyString().stripMargin()
  }

  @Override
  def getFileName() {
    return 'report.json'
  }
}
