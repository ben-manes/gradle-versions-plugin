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

  def writeToConsole(printStream) {
    def responseObject = buildBaseObject()

    writeHeader(printStream)
    printStream.println new JsonBuilder(responseObject).toPrettyString().stripMargin()
  }

  @Override
  def writeToFile(printStream) {
    def responseObject = buildBaseObject()
    printStream.println new JsonBuilder(responseObject).toPrettyString().stripMargin()
  }

  @Override
  def getFileName() {
    return 'report.json'
  }

  protected def writeHeader(printStream) {
    printStream.println """
      |------------------------------------------------------------
      |${project.path} Project Dependency Updates (json)
      |------------------------------------------------------------""".stripMargin()
  }
}
