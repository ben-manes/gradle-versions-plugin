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
  void write(Appendable printStream, Result result) {

    XStream xstream = new XStream()
    xstream.aliasSystemAttribute(null, "class") // Removes attributes={class=sorted-set}
    xstream.alias("response", Result)
    xstream.alias("available", VersionAvailable)
    xstream.alias("exceededDependency", DependencyLatest)
    xstream.alias("outdatedDependency", DependencyOutdated)
    xstream.alias("unresolvedDependency", DependencyUnresolved)
    xstream.alias("dependency", Dependency)
    xstream.alias("group", DependenciesGroup)

    printStream.println("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
    printStream.println(xstream.toXML(result).stripMargin())
  }

  @Override
  String getFileExtension() {
    return "xml"
  }
}
