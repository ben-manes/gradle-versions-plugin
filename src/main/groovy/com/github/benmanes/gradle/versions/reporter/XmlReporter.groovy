package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.*
import com.thoughtworks.xstream.XStream
import groovy.transform.TupleConstructor

/**
 * A xml reporter for the dependency updates results.
 *
 * @author Zenedith (zenedith@wp.pl)
 */
@TupleConstructor(callSuper = true, includeSuperProperties = true, includeSuperFields = true)
class XmlReporter extends AbstractReporter implements Reporter {

  @Override
  def write(printStream) {
    def responseObject = buildBaseObject()

    XStream xstream = new XStream()
    xstream.alias("response", Result.class)
    xstream.alias("available", VersionAvailable.class)
    xstream.alias("exceededDependency", DependencyLatest.class)
    xstream.alias("outdatedDependency", DependencyOutdated.class)
    xstream.alias("unresolvedDependency", DependencyUnresolved.class)
    xstream.alias("dependency", Dependency.class)
    xstream.alias("group", DependenciesGroup.class)

    printStream.println '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    printStream.println xstream.toXML(responseObject).stripMargin()
  }

  @Override
  def getFileName() {
    return 'report.xml'
  }
}
