package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.Result
import groovy.json.JsonBuilder
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor

/**
 * A json reporter for the dependency updates results.
 */
@CompileStatic
@TupleConstructor(callSuper = true, includeSuperProperties = true, includeSuperFields = true)
class JsonReporter extends AbstractReporter {

  def write(printStream, Result result) {
    printStream.println new JsonBuilder(result).toPrettyString().stripMargin()
  }

  @Override
  def getFileExtension() {
    return 'json'
  }
}
