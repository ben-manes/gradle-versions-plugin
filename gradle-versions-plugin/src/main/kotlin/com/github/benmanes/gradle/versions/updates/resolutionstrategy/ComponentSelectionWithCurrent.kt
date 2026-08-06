package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.VersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionSelectorScheme
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser

class ComponentSelectionWithCurrent(
  private val delegate: ComponentSelection,
  val currentVersion: String,
  /** The constraint the build declared for this module, null when no declaration named it. */
  val versionConstraint: VersionConstraint?,
  /**
   * The constraints the platforms this module's consumer depends on state for it, empty when this
   * module's declaration states its own version or no consumed platform bounds it.
   */
  val platformVersionConstraints: List<VersionConstraint>,
) : ComponentSelection by delegate {
  /** Retained so the arity released before the constraint was added still links. */
  constructor(
    delegate: ComponentSelection,
    currentVersion: String,
  ) : this(delegate, currentVersion, null, emptyList())

  /**
   * Retains the arity the declared [VersionConstraint] arrived with, in case a release ships it
   * before this change.
   */
  constructor(
    delegate: ComponentSelection,
    currentVersion: String,
    versionConstraint: VersionConstraint?,
  ) : this(delegate, currentVersion, versionConstraint, emptyList())

  /**
   * Whether the candidate lies within the bound the declaration stated, read the way dependency
   * resolution reads it, so that a range is honored rather than compared as a string.
   *
   * Only `strictly` and `reject` bound a candidate. A `require` version is a floor that resolution
   * may rise above, and a `prefer` version is consulted only to break a tie, so neither excludes an
   * upgrade the build already permits. True for a module that declared no bound at all.
   *
   * A module whose declaration states no version is additionally bounded by the constraints the
   * platforms the consumer depends on state for it, and the version currently selected is always
   * within bound.
   */
  val satisfiesDeclaredBound: Boolean
    get() =
      DeclaredBound.accepts(versionConstraint, candidate.version) &&
        DeclaredBound.acceptsPlatformSupplied(platformVersionConstraints, currentVersion, candidate.version)

  override fun toString(): String {
    return """\
ComponentSelectionWithCurrent{
    group="${candidate.group.orEmpty()}",
    module="${candidate.module}",
    version="${candidate.version}",
    currentVersion="$currentVersion",
    versionConstraint="$versionConstraint",
    platformVersionConstraints="$platformVersionConstraints",
}"""
  }
}

/**
 * Reads a declared selector with the parser dependency resolution itself uses, as Gradle publishes
 * no API for it; https://github.com/gradle/gradle/issues/13748 asks for one and remains open, and
 * its own worked example is this. A release that moves the parser leaves the bound unknown rather
 * than failing the report, which is why the linkage failure is answered instead of thrown.
 */
private object DeclaredBound {
  private val scheme: DefaultVersionSelectorScheme? by lazy {
    try {
      DefaultVersionSelectorScheme(DefaultVersionComparator(), VersionParser())
    } catch (e: LinkageError) {
      null
    }
  }

  fun accepts(
    constraint: VersionConstraint?,
    candidate: String,
  ): Boolean {
    val parser = scheme
    if (parser == null || constraint == null) {
      return true
    }
    return try {
      val strict = constraint.strictVersion
      if (strict.isNotEmpty() && !parser.parseSelector(strict).accept(candidate)) {
        false
      } else {
        constraint.rejectedVersions.none { parser.parseSelector(it).accept(candidate) }
      }
    } catch (e: LinkageError) {
      true
    }
  }

  /**
   * Whether the candidate lies within every one of the versions the consumed platforms require for
   * this module, read the same way [accepts] reads a declared bound. Requiring each edge to admit
   * the candidate is stricter than the version resolution itself settles on, which is deliberate:
   * the report offers an upgrade only when no consumed platform stands against it. The version
   * currently selected is always accepted, since a transitive requirement can push the merged
   * selection above what a platform alone would choose.
   */
  fun acceptsPlatformSupplied(
    constraints: List<VersionConstraint>,
    currentVersion: String,
    candidate: String,
  ): Boolean {
    val parser = scheme
    if (parser == null || constraints.isEmpty() || candidate == currentVersion) {
      return true
    }
    return try {
      constraints.all { constraint ->
        val required = constraint.requiredVersion
        (required.isEmpty() || parser.parseSelector(required).accept(candidate)) &&
          constraint.rejectedVersions.none { parser.parseSelector(it).accept(candidate) }
      }
    } catch (e: LinkageError) {
      true
    }
  }
}
