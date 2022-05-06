package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.DependenciesGroup
import com.github.benmanes.gradle.versions.reporter.result.Dependency
import com.github.benmanes.gradle.versions.reporter.result.DependencyLatest
import com.github.benmanes.gradle.versions.reporter.result.DependencyOutdated
import com.github.benmanes.gradle.versions.reporter.result.DependencyUnresolved
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.reporter.result.VersionAvailable
import com.thoughtworks.xstream.XStream
import org.gradle.api.Project
import java.io.OutputStream

/**
 * A xml reporter for the dependency updates results.
 */
class XmlReporter @JvmOverloads constructor(
  override val project: Project,
  override val revision: String,
  override val gradleReleaseChannel: String,
) : AbstractReporter(project, revision, gradleReleaseChannel) {
  override fun write(printStream: OutputStream, result: Result) {
    val xStream = XStream().apply {
      aliasSystemAttribute(null, "class") // Removes attributes={class=sorted-set}
      alias("response", Result::class.java)
      alias("available", VersionAvailable::class.java)
      alias("exceededDependency", DependencyLatest::class.java)
      alias("outdatedDependency", DependencyOutdated::class.java)
      alias("unresolvedDependency", DependencyUnresolved::class.java)
      alias("dependency", Dependency::class.java)
      alias("group", DependenciesGroup::class.java)
    }

    printStream.println("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
    printStream.println(xStream.toXML(result).trimMargin())
  }

  override fun getFileExtension(): String {
    return "xml"
  }
}
