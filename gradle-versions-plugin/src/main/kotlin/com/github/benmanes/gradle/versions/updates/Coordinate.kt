package com.github.benmanes.gradle.versions.updates

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ModuleVersionSelector
import org.gradle.api.artifacts.VersionConstraint
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint

/**
 * The dependency's coordinate.
 */
class Coordinate(
  groupId: String?,
  artifactId: String?,
  version: String?,
  val userReason: String? = null,
  versionConstraint: VersionConstraint? = null,
) : Comparable<Coordinate> {
  val groupId: String = groupId ?: "none"
  val artifactId: String = artifactId ?: "none"
  val version: String = version ?: "none"

  /**
   * The constraint the declaration stated, which the resolved version alone does not carry. Held as
   * an immutable copy, since a selection rule is handed this and the declaration's own constraint
   * is mutable. Null for a coordinate no declaration was matched to, such as the module a
   * substitution rule resolved to, or one rebuilt from a partial result.
   *
   * Copying it needs a class Gradle does not publish, and every coordinate passes through here, so a
   * release that moves the class leaves the constraint unknown rather than failing the whole report.
   */
  val versionConstraint: VersionConstraint? =
    try {
      versionConstraint?.let { DefaultImmutableVersionConstraint.of(it) }
    } catch (e: LinkageError) {
      null
    }

  val key: Key
    get() = Key(groupId, artifactId)

  /** Retains the arity that callers outside Kotlin construct this with. */
  constructor(
    groupId: String?,
    artifactId: String?,
    version: String?,
    userReason: String?,
  ) : this(groupId, artifactId, version, userReason, null)

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
      return Coordinate(
        dependency.group,
        dependency.name,
        dependency.version,
        dependency.reason,
        dependency.versionConstraint,
      )
    }

    // A dependency constraint reaches this overload as well, and unlike a bare selector it declares
    // a version constraint and a reason of its own.
    fun from(selector: ModuleVersionSelector): Coordinate {
      val constraint = selector as? DependencyConstraint
      return Coordinate(
        selector.group,
        selector.name,
        selector.version,
        constraint?.reason,
        constraint?.versionConstraint,
      )
    }

    fun from(identifier: ModuleVersionIdentifier): Coordinate {
      return Coordinate(identifier.group, identifier.name, identifier.version)
    }

    fun from(dependency: Dependency): Coordinate {
      return Coordinate(
        dependency.group,
        dependency.name,
        dependency.version,
        dependency.reason,
        (dependency as? ExternalDependency)?.versionConstraint,
      )
    }

    fun keyFrom(selector: ModuleVersionSelector): Key {
      return Key(selector.group, selector.name)
    }

    fun from(
      identifier: ModuleVersionIdentifier,
      declared: Map<Key, Coordinate?>,
    ): Coordinate {
      val declaration = declared[Key(identifier.group, identifier.name)]
      return Coordinate(
        identifier.group,
        identifier.name,
        identifier.version,
        declaration?.userReason,
        declaration?.versionConstraint,
      )
    }

    fun from(identifier: ModuleComponentIdentifier): Coordinate {
      return Coordinate(identifier.group, identifier.module, identifier.version)
    }

    fun from(selector: ModuleComponentSelector): Coordinate {
      return Coordinate(selector.group, selector.module, selector.version)
    }
  }
}
