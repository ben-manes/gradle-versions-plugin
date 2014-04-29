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
class XmlReporter extends ObjectReporter implements Reporter {

  def writeTo(printStream) {
    def responseObject = buildBaseObject()

    XStream xstream = new XStream()
    xstream.alias("response", Result.class)
    xstream.alias("available", VersionAvailable.class)
    xstream.alias("exceededDependency", DependencyLatest.class)
    xstream.alias("outdatedDependency", DependencyOutdated.class)
    xstream.alias("unresolvedDependency", DependencyUnresolved.class)
    xstream.alias("dependency", Dependency.class)
    xstream.alias("group", DependenciesGroup.class)

    writeHeader(printStream)
    printStream.println xstream.toXML(responseObject).stripMargin()
  }

  protected def writeHeader(printStream) {
    printStream.println """
      |------------------------------------------------------------
      |${project.path} Project Dependency Updates (xml)
      |------------------------------------------------------------""".stripMargin()
  }
}
