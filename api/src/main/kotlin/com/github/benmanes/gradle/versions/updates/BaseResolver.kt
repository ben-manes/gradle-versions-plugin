package com.github.benmanes.gradle.versions.updates

import groovy.util.XmlSlurper
import groovy.util.slurpersupport.GPathResult
import groovy.util.slurpersupport.NodeChildren
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import java.io.File

abstract class BaseResolver {

  abstract fun supportsConstraints(configuration: Configuration): Boolean

  fun getResolvableDependencies(configuration: Configuration): List<Coordinate> {
    val coordinates = configuration.dependencies
      .filter { dependency -> dependency is ExternalDependency }
      .map { dependency ->
        Coordinate.from(dependency)
      } as MutableList<Coordinate>

    if (supportsConstraints(configuration)) {
      configuration.dependencyConstraints.forEach { dependencyConstraint ->
        coordinates.add(Coordinate.from(dependencyConstraint))
      }
    }
    return coordinates
  }

  companion object {
    @JvmStatic
    fun getUrlFromPom(file: File): String? {
      val pom = XmlSlurper(false, false).parse(file)
      val url = (pom.getProperty("url") as NodeChildren?)?.text()
      return url
        ?: ((pom.getProperty("scm") as NodeChildren?)?.getProperty("url") as NodeChildren?)?.text()
    }

    @JvmStatic
    fun getParentFromPom(file: File): ModuleVersionIdentifier? {
      val pom = XmlSlurper(false, false).parse(file)
      val parent: GPathResult? = pom.getProperty("parent") as NodeChildren?
      if (parent != null) {
        val groupId = (parent.getProperty("groupId") as NodeChildren?)?.text()
        val artifactId = (parent.getProperty("artifactId") as NodeChildren?)?.text()
        val version = (parent.getProperty("version") as NodeChildren?)?.text()
        if (groupId != null && artifactId != null && version != null) {
          return DefaultModuleVersionIdentifier.newId(groupId, artifactId, version)
        }
      }
      return null
    }

    class ProjectUrl {
      var resolved: Boolean = false
      var url: String? = null
    }
  }
}
