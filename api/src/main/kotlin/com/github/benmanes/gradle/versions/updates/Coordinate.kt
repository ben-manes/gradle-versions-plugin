package com.github.benmanes.gradle.versions.updates

import org.codehaus.groovy.runtime.DefaultGroovyMethods.asBoolean
import org.codehaus.groovy.runtime.DefaultGroovyMethods.getMetaClass
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ModuleVersionSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

/**
 * The dependency's coordinate.
 */
class Coordinate @JvmOverloads constructor(
  groupId: String?,
  artifactId: String?,
  version: String?,
  userReason: String?
) : Comparable<Coordinate> {
  val groupId: String
  val artifactId: String
  val version: String
  val userReason: String?

  val key: Key
    get() = Key(groupId, artifactId)

  init {
    this.groupId = groupId ?: "none"
    this.artifactId = artifactId ?: "none"
    this.version = version ?: "none"
    this.userReason = userReason
  }

  override fun toString(): String {
    return "$groupId:$artifactId:$version"
  }

  override fun compareTo(other: Coordinate): Int {
    return compareValuesBy(
      this, other,
      { it.key },
      { it.version },
    )
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Coordinate) return false
    if (groupId != other.groupId) return false
    if (artifactId != other.artifactId) return false
    if (version != other.version) return false
    return true
  }

  override fun hashCode(): Int {
    var result = groupId.hashCode()
    result = 31 * result + artifactId.hashCode()
    result = 31 * result + version.hashCode()
    return result
  }

  data class Key(val groupId: String, val artifactId: String) : Comparable<Key> {
    override fun toString(): String {
      return "$groupId:$artifactId"
    }

    override fun compareTo(other: Key): Int {
      return compareValuesBy(
        this, other,
        { it.groupId },
        { it.artifactId },
      )
    }
  }

  companion object {
    @JvmStatic
    fun from(dependency: ExternalModuleDependency): Coordinate {
      var userReason: String? = null
      if (asBoolean(getMetaClass(dependency).respondsTo(dependency, "getReason"))) {
        userReason = dependency.reason
      }
      return Coordinate(dependency.group, dependency.name, dependency.version, userReason)
    }

    @JvmStatic
    fun from(selector: ModuleVersionSelector): Coordinate {
      return Coordinate(selector.group, selector.name, selector.version, userReason = null)
    }

    @JvmStatic
    fun from(identifier: ModuleVersionIdentifier): Coordinate {
      return Coordinate(identifier.group, identifier.name, identifier.version, userReason = null)
    }

    @JvmStatic
    fun from(dependency: Dependency): Coordinate {
      var userReason: String? = null
      if (asBoolean(getMetaClass(dependency).respondsTo(dependency, "getReason"))) {
        userReason = dependency.reason
      }
      return Coordinate(dependency.group, dependency.name, dependency.version, userReason)
    }

    @JvmStatic
    fun keyFrom(selector: ModuleVersionSelector): Key {
      return Key(selector.group, selector.name)
    }

    @JvmStatic
    fun from(identifier: ModuleVersionIdentifier, declared: Map<Key, Coordinate?>): Coordinate {
      return Coordinate(
        identifier.group, identifier.name, identifier.version,
        declared.getOrDefault(Key(identifier.group, identifier.name), null)?.userReason
      )
    }

    @JvmStatic
    fun from(identifier: ModuleComponentIdentifier): Coordinate {
      return Coordinate(identifier.group, identifier.module, identifier.version, userReason = null)
    }
  }
}
