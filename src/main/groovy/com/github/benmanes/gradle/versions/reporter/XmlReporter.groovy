package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.*
import com.thoughtworks.xstream.XStream
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor

/**
 * A xml reporter for the dependency updates results.
 */
@CompileStatic
@TupleConstructor(callSuper = true, includeSuperProperties = true, includeSuperFields = true)
class XmlReporter extends AbstractReporter {

  @Override
  def write(printStream, Result result) {

    XStream xStream = new XStream()
    xStream.with {
      alias('response', Result)
      alias('available', VersionAvailable)
      alias('exceededDependency', DependencyLatest)
      alias('outdatedDependency', DependencyOutdated)
      alias('unresolvedDependency', DependencyUnresolved)
      alias('dependency', Dependency)
      alias('group', DependenciesGroup)
    }

    printStream.println '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    printStream.println xStream.toXML(result).stripMargin()
  }

  @Override
  def getFileExtension() {
    return 'xml'
  }
}
