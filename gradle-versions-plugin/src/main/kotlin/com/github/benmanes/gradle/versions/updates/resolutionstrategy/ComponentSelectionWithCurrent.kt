package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.VersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionSelectorScheme
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser

class ComponentSelectionWithCurrent internal constructor(
  private val delegate: ComponentSelection,
  val currentVersion: String,
  /** The constraint the build declared for this module, null when no declaration matched it. */
  val versionConstraint: VersionConstraint?,
  /**
   * The constraints the platforms this module's consumer depends on set for it, empty when this
   * module's declaration has its own version or no consumed platform bounds it.
   */
  val platformVersionConstraints: List<VersionConstraint>,
  /**
   * Whether the declaration sits on a script classpath, where a dynamic required version is read
   * as a bound.
   */
  private val onScriptClasspath: Boolean = false,
) : ComponentSelection by delegate {
  /** Retained so the arity released before the constraint was added still links. */
  constructor(
    delegate: ComponentSelection,
    currentVersion: String,
  ) : this(delegate, currentVersion, null, emptyList())

  /**
   * Whether the candidate lies within the bound written on the declaration, read the way dependency
   * resolution reads it, so that a range is honored rather than compared as a string.
   *
   * Only `strictly` and `reject` bound a candidate. A `require` version is a floor that resolution
   * may rise above, and a `prefer` version is consulted only to break a tie, so neither excludes an
   * upgrade the build already permits. True for a module that declared no bound at all.
   *
   * A *dynamic* required version, such as `[1.0, 2[` or `1.+`, is the one exception, and it applies
   * on a script classpath alone. A plugin marker has a single declaration and no competing
   * requirement, so the interval is the version declared rather than a floor. It is also the only
   * form a version-catalog plugin alias survives as, since Gradle flattens the alias to a bare
   * required version on the marker; reading it is what lets both declaration forms answer alike,
   * which they must, as the marker is identical either way. The version currently selected is
   * always within bound, since a transitive requirement on a classpath can push the selection past
   * the interval.
   * https://github.com/ben-manes/gradle-versions-plugin/issues/755
   *
   * A module declared without a version is additionally bounded by the constraints the platforms
   * the consumer depends on set for it, and the version currently selected is always within
   * bound.
   */
  val satisfiesDeclaredBound: Boolean
    get() =
      DeclaredBound.accepts(versionConstraint, candidate.version, currentVersion, onScriptClasspath) &&
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
 * than failing the report, which is why the linkage failure is caught instead of thrown.
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
    currentVersion: String,
    dynamicRequiredBounds: Boolean,
  ): Boolean {
    val parser = scheme
    if (parser == null || constraint == null) {
      return true
    }
    return try {
      val strict = constraint.strictVersion
      val outOfBound =
        if (strict.isNotEmpty()) {
          !parser.parseSelector(strict).accept(candidate)
        } else {
          // The selected version is left in bound, since a transitive requirement can push the
          // selection past an interval that is only a floor.
          dynamicRequiredBounds && candidate != currentVersion &&
            excludedByDynamicRequired(constraint.requiredVersion, candidate)
        }
      if (outOfBound) {
        false
      } else {
        constraint.rejectedVersions.none { parser.parseSelector(it).accept(candidate) }
      }
    } catch (e: LinkageError) {
      true
    }
  }

  /**
   * Whether the required version is dynamic and excludes the candidate. A selector admitting every
   * version, `latest.release` among them, therefore bounds nothing. The catch is a guard for a
   * release that moves the parser or rejects a form Gradle itself accepted, which leaves the bound
   * unknown rather than failing the whole report, as [accepts] does for a linkage failure.
   */
  private fun excludedByDynamicRequired(
    version: String,
    candidate: String,
  ): Boolean {
    val parser = scheme
    if (parser == null || version.isEmpty()) {
      return false
    }
    return try {
      val selector = parser.parseSelector(version)
      selector.isDynamic && !selector.accept(candidate)
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Whether the candidate lies within every one of the versions the consumed platforms set for
   * this module, read the same way [accepts] reads a declared bound. Requiring each edge to admit
   * the candidate is stricter than the version resolution itself settles on, which is deliberate:
   * an upgrade is offered only when every consumed platform admits it. The version
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
        val strict = constraint.strictVersion
        val required = constraint.requiredVersion
        (strict.isEmpty() || parser.parseSelector(strict).accept(candidate)) &&
          (required.isEmpty() || parser.parseSelector(required).accept(candidate)) &&
          constraint.rejectedVersions.none { parser.parseSelector(it).accept(candidate) }
      }
    } catch (e: LinkageError) {
      true
    }
  }
}
