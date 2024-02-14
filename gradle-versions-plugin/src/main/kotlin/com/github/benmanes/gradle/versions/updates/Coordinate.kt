package com.github.benmanes.gradle.versions.updates

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ModuleVersionSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

/**
 * The dependency's coordinate.
 */
class Coordinate(
  groupId: String?,
  artifactId: String?,
  version: String?,
  userReason: String? = null,
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
      this,
      other,
      { it.key },
      { it.version },
    )
  }

  // Previous implementation did not include "userReason"
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
        this,
        other,
        { it.groupId },
        { it.artifactId },
      )
    }
  }

  companion object {
    fun from(dependency: ExternalModuleDependency): Coordinate {
      return Coordinate(dependency.group, dependency.name, dependency.version, dependency.reason)
    }

    fun from(selector: ModuleVersionSelector): Coordinate {
      return Coordinate(selector.group, selector.name, selector.version)
    }

    fun from(identifier: ModuleVersionIdentifier): Coordinate {
      return Coordinate(identifier.group, identifier.name, identifier.version)
    }

    fun from(dependency: Dependency): Coordinate {
      return Coordinate(dependency.group, dependency.name, dependency.version, dependency.reason)
    }

    fun keyFrom(selector: ModuleVersionSelector): Key {
      return Key(selector.group, selector.name)
    }

    fun from(
      identifier: ModuleVersionIdentifier,
      declared: Map<Key, Coordinate?>,
    ): Coordinate {
      return Coordinate(
        identifier.group,
        identifier.name,
        identifier.version,
        declared.getOrDefault(Key(identifier.group, identifier.name), null)?.userReason,
      )
    }

    fun from(identifier: ModuleComponentIdentifier): Coordinate {
      return Coordinate(identifier.group, identifier.module, identifier.version)
    }
  }
}
