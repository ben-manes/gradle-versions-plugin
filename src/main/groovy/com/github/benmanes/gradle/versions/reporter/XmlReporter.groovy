package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.DependenciesGroup
import com.github.benmanes.gradle.versions.reporter.result.Dependency
import com.github.benmanes.gradle.versions.reporter.result.DependencyLatest
import com.github.benmanes.gradle.versions.reporter.result.DependencyOutdated
import com.github.benmanes.gradle.versions.reporter.result.DependencyUnresolved
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.reporter.result.VersionAvailable
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

    XStream xstream = new XStream()
    xstream.with {
      alias('response', Result)
      alias('available', VersionAvailable)
      alias('exceededDependency', DependencyLatest)
      alias('outdatedDependency', DependencyOutdated)
      alias('unresolvedDependency', DependencyUnresolved)
      alias('dependency', Dependency)
      alias('group', DependenciesGroup)
    }

    printStream.println '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    printStream.println xstream.toXML(result).stripMargin()
  }

  @Override
  def getFileExtension() {
    return 'xml'
  }
}
