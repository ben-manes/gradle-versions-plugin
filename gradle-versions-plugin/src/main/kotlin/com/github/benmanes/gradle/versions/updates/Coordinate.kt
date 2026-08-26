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

// The parameter list is the one 0.59.0 released. A default argument compiles callers against a
// synthetic constructor with an argument mask, so adding a parameter here, even a defaulted one,
// leaves a caller of `Coordinate(group, name, version)` linking against a signature that no longer
// exists. The constraint arrives through a secondary constructor instead.

/**
 * The dependency's coordinate.
 */
class Coordinate(
  groupId: String?,
  artifactId: String?,
  version: String?,
  val userReason: String? = null,
) : Comparable<Coordinate> {
  val groupId: String = groupId ?: "none"
  val artifactId: String = artifactId ?: "none"
  val version: String = version ?: "none"

  /**
   * The constraint written on the declaration, which the resolved version alone does not include.
   * Kept as an immutable copy, since a selection rule is given this and the declaration's own
   * constraint is mutable. Null for a coordinate no declaration was matched to, such as the module
   * a substitution rule resolved to, or one rebuilt from a partial result.
   *
   * Copying it needs a class Gradle does not publish, and every coordinate passes through here, so a
   * release that moves the class leaves the constraint unknown rather than failing the whole report.
   */
  var versionConstraint: VersionConstraint? = null
    private set

  /**
   * The constraints the platforms this module's consumer depends on set for it, empty when no
   * declaration referenced this module without a version or no consumed platform bounds it. A
   * release that moves the copying class leaves this empty rather than failing the whole report.
   */
  var platformVersionConstraints: List<VersionConstraint> = emptyList()
    private set

  /**
   * The build tree paths of the platform projects the build imports this module through, empty
   * otherwise.
   */
  var platformProjects: List<String> = emptyList()
    private set

  /**
   * The platforms whose constraint is the version reported, empty otherwise. A platform project
   * appears as its build tree path and an external one as its group and module.
   */
  var constrainedBy: List<String> = emptyList()
    private set

  val key: Key
    get() = Key(groupId, artifactId)

  /**
   * The latest version found for this row, set only when one declared version of the module has
   * different latest versions across the aggregated projects. Null on every other row, so that an
   * unsplit row has the identity it always had.
   *
   * It arrives through a constructor rather than a setter, because it is part of [equals],
   * [hashCode] and [compareTo], and a coordinate already in a hash map or a sorted set becomes
   * unreachable and mis-ordered if its identity changes underneath the collection.
   */
  internal var divergentLatest: String? = null
    private set

  /**
   * As above, additionally taking the latest version that separates this row from the other rows
   * of its declared version. Distinct from the constructor below by the type of its last parameter.
   */
  internal constructor(
    groupId: String?,
    artifactId: String?,
    version: String?,
    userReason: String?,
    divergentLatest: String?,
  ) : this(groupId, artifactId, version, userReason) {
    this.divergentLatest = divergentLatest
  }

  internal constructor(
    groupId: String?,
    artifactId: String?,
    version: String?,
    userReason: String?,
    versionConstraint: VersionConstraint?,
  ) : this(groupId, artifactId, version, userReason) {
    this.versionConstraint =
      try {
        versionConstraint?.let { DefaultImmutableVersionConstraint.of(it) }
      } catch (e: LinkageError) {
        null
      }
  }

  internal constructor(
    groupId: String?,
    artifactId: String?,
    version: String?,
    userReason: String?,
    versionConstraint: VersionConstraint?,
    platformVersionConstraints: List<VersionConstraint>,
  ) : this(groupId, artifactId, version, userReason, versionConstraint) {
    this.platformVersionConstraints =
      try {
        platformVersionConstraints.map { DefaultImmutableVersionConstraint.of(it) }
      } catch (e: LinkageError) {
        emptyList()
      }
  }

  internal constructor(
    groupId: String?,
    artifactId: String?,
    version: String?,
    userReason: String?,
    versionConstraint: VersionConstraint?,
    platformVersionConstraints: List<VersionConstraint>,
    platformProjects: List<String>,
  ) : this(groupId, artifactId, version, userReason, versionConstraint, platformVersionConstraints) {
    this.platformProjects = platformProjects
  }

  internal constructor(
    groupId: String?,
    artifactId: String?,
    version: String?,
    userReason: String?,
    versionConstraint: VersionConstraint?,
    platformVersionConstraints: List<VersionConstraint>,
    platformProjects: List<String>,
    constrainedBy: List<String>,
  ) : this(
    groupId,
    artifactId,
    version,
    userReason,
    versionConstraint,
    platformVersionConstraints,
    platformProjects,
  ) {
    this.constrainedBy = constrainedBy
  }

  override fun toString(): String {
    return "$groupId:$artifactId:$version"
  }

  // The split marker is compared as a string rather than as a version. It is here to keep the
  // ordering consistent with equals, which a sorted set requires, and the order it imposes among
  // the rows of one declared version is never the order the report prints them in.
  override fun compareTo(other: Coordinate): Int {
    return compareValuesBy(
      this,
      other,
      { it.key },
      { it.version },
      { it.divergentLatest.orEmpty() },
    )
  }

  // Previous implementation did not include "userReason"
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Coordinate) return false
    if (groupId != other.groupId) return false
    if (artifactId != other.artifactId) return false
    if (version != other.version) return false
    if (divergentLatest != other.divergentLatest) return false
    return true
  }

  override fun hashCode(): Int {
    var result = groupId.hashCode()
    result = 31 * result + artifactId.hashCode()
    result = 31 * result + version.hashCode()
    result = 31 * result + divergentLatest.hashCode()
    return result
  }

  /** A version constraint from a consumed platform, paired with the platform it came from. */
  internal data class PlatformConstraint(
    val source: String,
    val constraint: VersionConstraint,
  ) {
    /**
     * Whether the constraint is exactly this version. A range never matches, even when the range is
     * what picked the version, so a platform constraining by a range is left out rather than
     * included on a guess.
     */
    fun matches(version: String): Boolean =
      constraint.strictVersion == version ||
        constraint.requiredVersion == version ||
        constraint.preferredVersion == version
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

    /**
     * As above, additionally attaching the constraints the consumed platforms place on it.
     *
     * Only a platform whose constraint is the resolved version is included, since one that lost out
     * to a higher requirement is not why the row shows that version. Every collected constraint is
     * still kept for the selection rules, where the question is whether any consumed platform rules
     * out a candidate, not which one produced the current version.
     */
    internal fun from(
      identifier: ModuleVersionIdentifier,
      declared: Map<Key, Coordinate?>,
      platformConstraints: Map<Key, List<PlatformConstraint>>,
    ): Coordinate {
      val key = Key(identifier.group, identifier.name)
      val declaration = declared[key]
      val constraints = platformConstraints[key].orEmpty()
      return Coordinate(
        identifier.group,
        identifier.name,
        identifier.version,
        declaration?.userReason,
        declaration?.versionConstraint,
        constraints.map { it.constraint },
        emptyList(),
        constraints.filter { it.matches(identifier.version) }.map { it.source }.distinct().sorted(),
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
