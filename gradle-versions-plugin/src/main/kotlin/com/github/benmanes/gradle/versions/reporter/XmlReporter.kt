package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.Dependency
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.reporter.result.VersionAvailable
import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResult
import org.gradle.api.Project
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * A XML reporter for the dependency updates results.
 */
class XmlReporter(
  override val project: Project,
  override val revision: String,
  override val gradleReleaseChannel: String,
) : AbstractReporter(project, revision, gradleReleaseChannel) {

  override fun write(printStream: OutputStream, result: Result) {
    val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val document = documentBuilder.newDocument()
    document.setXmlStandalone(true)

    val response = document.createElement("response")
    document.appendChild(response)
    appendTextChild(document, response, "count", result.count)

    writeCurrentSection(result, document, response)
    writeOutdatedSection(result, document, response)
    writeExceededSection(result, document, response)
    writeUndeclaredSection(result, document, response)
    writeUnresolvedSection(result, document, response)
    writeGradle(result, document, response)

    val transformerFactory = TransformerFactory.newInstance()
    transformerFactory.setAttribute("indent-number", 2)

    val transformer = transformerFactory.newTransformer()
    transformer.setOutputProperty(OutputKeys.INDENT, "yes")
    transformer.setOutputProperty(OutputKeys.STANDALONE, "yes")

    val source = DOMSource(document)
    val resultStream = StreamResult(printStream)
    transformer.transform(source, resultStream)
  }

  private fun writeCurrentSection(result: Result, document: Document, response: Element) {
    val current = document.createElement("current")
    response.appendChild(current)
    appendTextChild(document, current, "count", result.current.count)

    val dependencies = document.createElement("dependencies")
    current.appendChild(dependencies)
    for (dependency in result.current.dependencies) {
      writeDependency(document, dependencies, "dependency", dependency)
    }
  }

  private fun writeOutdatedSection(result: Result, document: Document, response: Element) {
    val outdated = document.createElement("outdated")
    response.appendChild(outdated)
    appendTextChild(document, outdated, "count", result.outdated.count)

    val dependencies = document.createElement("dependencies")
    outdated.appendChild(dependencies)
    for (dependency in result.outdated.dependencies) {
      var element = writeDependency(document, dependencies, "outdatedDependency", dependency)
      writeVersionAvailable(document, element, dependency.available)
    }
  }

  private fun writeVersionAvailable(
    document: Document,
    dependencyElement: Element,
    version: VersionAvailable
  ) {
    val available = document.createElement("available")
    dependencyElement.appendChild(available)

    appendTextChild(document, available, "release", version.release)
    appendTextChild(document, available, "milestone", version.milestone)
    appendTextChild(document, available, "integration", version.integration)
  }

  private fun writeExceededSection(result: Result, document: Document, response: Element) {
    val exceeded = document.createElement("exceeded")
    response.appendChild(exceeded)
    appendTextChild(document, exceeded, "count", result.exceeded.count)

    val dependencies = document.createElement("dependencies")
    exceeded.appendChild(dependencies)
    for (dependency in result.exceeded.dependencies) {
      var element = writeDependency(document, dependencies, "exceededDependency", dependency)
      appendTextChild(document, element, "latest", dependency.latest)
    }
  }

  private fun writeUndeclaredSection(result: Result, document: Document, response: Element) {
    val undeclared = document.createElement("undeclared")
    response.appendChild(undeclared)
    appendTextChild(document, undeclared, "count", result.undeclared.count)

    val dependencies = document.createElement("dependencies")
    undeclared.appendChild(dependencies)
    for (dependency in result.undeclared.dependencies) {
      writeDependency(document, dependencies, "dependency", dependency)
    }
  }

  private fun writeUnresolvedSection(result: Result, document: Document, response: Element) {
    val unresolved = document.createElement("unresolved")
    response.appendChild(unresolved)
    appendTextChild(document, unresolved, "count", result.unresolved.count)

    val dependencies = document.createElement("dependencies")
    unresolved.appendChild(dependencies)
    for (dependency in result.unresolved.dependencies) {
      val element = writeDependency(document, dependencies, "unresolvedDependency", dependency)
      appendTextChild(document, element, "reason", dependency.reason)
    }
  }

  private fun writeGradle(result: Result, document: Document, response: Element) {
    val gradle = document.createElement("gradle")
    response.appendChild(gradle)
    appendTextChild(document, gradle, "enabled", result.gradle.enabled)

    writeGradleUpdate(document, gradle, "running", result.gradle.running)
    writeGradleUpdate(document, gradle, "current", result.gradle.current)
    writeGradleUpdate(document, gradle, "releaseCandidate", result.gradle.releaseCandidate)
    writeGradleUpdate(document, gradle, "nightly", result.gradle.nightly)
  }

  private fun writeGradleUpdate(
    document: Document,
    element: Element,
    name: String,
    update: GradleUpdateResult
  ) {
    val channel = document.createElement(name)
    element.appendChild(channel)

    appendTextChild(document, channel, "version", update.version)
    appendTextChild(document, channel, "isUpdateAvailable", update.isUpdateAvailable)
    appendTextChild(document, channel, "isFailure", update.isFailure)
    appendTextChild(document, channel, "reason", update.reason)
  }

  private fun writeDependency(
    document: Document,
    element: Element,
    name: String,
    dependency: Dependency
  ): Element {
    val dependencyElement = document.createElement(name)
    element.appendChild(dependencyElement)

    appendTextChild(document, dependencyElement, "group", dependency.group)
    appendTextChild(document, dependencyElement, "name", dependency.name)
    appendTextChild(document, dependencyElement, "version", dependency.version)
    appendTextChild(document, dependencyElement, "projectUrl", dependency.projectUrl)
    appendTextChild(document, dependencyElement, "userReason", dependency.userReason)
    return dependencyElement
  }

  private fun appendTextChild(
    document: Document,
    parent: Element,
    name: String,
    textContent: Any?
  ) {
    if (textContent != null) {
      val element = document.createElement(name)
      element.textContent = textContent.toString()
      parent.appendChild(element)
    }
  }

  override fun getFileExtension(): String {
    return "xml"
  }
}
