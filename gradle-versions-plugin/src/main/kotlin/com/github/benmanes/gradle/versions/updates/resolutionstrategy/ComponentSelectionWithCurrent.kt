package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import com.github.benmanes.gradle.versions.updates.VersionMapping
import com.github.benmanes.gradle.versions.updates.VersionStability
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.VersionConstraint
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionSelectorScheme
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import java.util.concurrent.ConcurrentHashMap

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
  /** Called when a rule reads [satisfiesDeclaredBound], so the deprecation can be warned once. */
  private val onDeprecatedBoundRead: () -> Unit = {},
  /** The check behind [isPreRelease], the built-in markers plus the convention added in the build. */
  private val preReleaseCheck: (String) -> Boolean = VersionStability::isPreRelease,
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
  internal val withinDeclaredBounds: Boolean
    get() =
      DeclaredBound.accepts(versionConstraint, candidate.version, currentVersion, onScriptClasspath) &&
        DeclaredBound.acceptsPlatformSupplied(platformVersionConstraints, currentVersion, candidate.version)

  /**
   * Whether the candidate lies within the declared bound, as [withinDeclaredBounds] reads it.
   *
   * Deprecated: the task's `rejectOutOfBounds` property leaves a candidate outside the declared
   * bound out of the report on its own, so nothing is left for a rule to reject.
   */
  @Deprecated("rejectOutOfBounds applies the declared bound; drop the clause from rejectVersionIf.")
  val satisfiesDeclaredBound: Boolean
    get() {
      onDeprecatedBoundRead()
      return withinDeclaredBounds
    }

  /**
   * Whether the candidate is an upgrade that lies outside the declared bound, which is what the
   * task's `rejectOutOfBounds` property leaves out of the report.
   *
   * The condition is narrower than [withinDeclaredBounds], which is evaluated for the candidate
   * alone. A candidate no newer than the version in use is not an upgrade at all, and rejecting it
   * would take the exceeded entry off the report while leaving nothing out. Each bound is applied
   * only where the version in use lies within it: a platform that a transitive requirement pushed
   * the version past bounds nothing, while a second platform pinning the version in use still does.
   */
  internal val isUpgradeOutOfDeclaredBounds: Boolean
    get() =
      DeclaredBound.isUpgradeOutOfBound(
        versionConstraint,
        platformVersionConstraints,
        currentVersion,
        candidate.version,
        onScriptClasspath,
      )

  /**
   * Returns whether [version] is a pre-release, by the same check the task's `rejectPreReleases`
   * property applies, the built-in markers plus any convention added with `preReleaseVersionIf`, so
   * a rule written with it leaves out what the property leaves out. Takes the version to read, for
   * a rule that reads some version other than the candidate's; the no-argument form is the check as
   * the property applies it.
   */
  fun isPreRelease(version: String): Boolean = preReleaseCheck(version)

  /**
   * Returns whether the candidate is a pre-release while the version in use is not, which is what
   * the task's `rejectPreReleases` property leaves out of the report. Narrower than [isPreRelease]
   * of the candidate alone: a build already on a pre-release is shown the next one, so for it this
   * is false. The same as `isPreRelease(candidate.version) && !isPreRelease(currentVersion)`.
   */
  fun isPreRelease(): Boolean = preReleaseCheck(candidate.version) && !preReleaseCheck(currentVersion)

  /**
   * Returns whether the candidate is an upgrade lying outside the declared bound, which is what the
   * task's `rejectOutOfBounds` property leaves out of the report.
   *
   * A function rather than a property, so that one spelling serves both DSLs. A `val` reads without
   * parentheses in Kotlin, and in Groovy as `outOfDeclaredBounds` or as `isOutOfDeclaredBounds()`, so
   * no single spelling would.
   */
  fun isOutOfDeclaredBounds(): Boolean = isUpgradeOutOfDeclaredBounds

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
 * Returns the constraint without the `strictly` that `enforcedPlatform(...)` adds at the version
 * declared, so that a later version of the platform is still reported, as it is for
 * `platform(...)`. A `strictly` written as a range is kept.
 *
 * https://github.com/ben-manes/gradle-versions-plugin/issues/1141
 */
internal fun withoutEnforcedPlatformStrictly(constraint: VersionConstraint): VersionConstraint? =
  DeclaredBound.withoutExactStrictly(constraint)

/**
 * Reads a declared selector with the parser dependency resolution itself uses, as Gradle publishes
 * no API for it; https://github.com/gradle/gradle/issues/13748 asks for one and remains open, and
 * its own worked example is this. A release that moves the parser, or a selector text it rejects
 * where Gradle itself accepted the declaration, leaves the bound unknown rather than failing the
 * report, which is why both the linkage failure and the throw are caught instead of thrown. The
 * built-in bound filter reads this for every candidate, so a throw that reached it would report the
 * whole configuration as skipped.
 */
private object DeclaredBound {
  // One parser for the scheme and the comparator, since each keeps a cache of every version
  // string it reads for the classloader's life.
  private val parser: VersionParser? by lazy {
    try {
      VersionParser()
    } catch (e: LinkageError) {
      null
    }
  }

  private val scheme: DefaultVersionSelectorScheme? by lazy {
    try {
      parser?.let { DefaultVersionSelectorScheme(DefaultVersionComparator(), it) }
    } catch (e: LinkageError) {
      null
    }
  }

  private val comparator: Comparator<String>? by lazy {
    try {
      parser?.let { VersionMapping.versionComparator(it) }
    } catch (e: LinkageError) {
      null
    }
  }

  // A declared selector is parsed once, since the filter is evaluated for every candidate of a
  // module and Gradle's parser caches no selector. The strings are the few a build declares.
  private val selectors = ConcurrentHashMap<String, VersionSelector>()

  private fun selector(
    scheme: DefaultVersionSelectorScheme,
    text: String,
  ): VersionSelector = selectors.computeIfAbsent(text) { scheme.parseSelector(it) }

  fun accepts(
    constraint: VersionConstraint?,
    candidate: String,
    currentVersion: String,
    dynamicRequiredBounds: Boolean,
  ): Boolean =
    // The selected version is left in bound, since a transitive requirement can push the selection
    // past an interval that is only a floor.
    admitsDeclared(constraint, candidate, dynamicRequiredBounds && candidate != currentVersion)

  /**
   * Whether the constraint the build declared admits the version. Split out of [accepts] so that
   * [isUpgradeOutOfBound] can ask it of the resolved version itself, which [accepts] leaves in bound
   * rather than testing against a dynamic required bound.
   */
  private fun admitsDeclared(
    constraint: VersionConstraint?,
    version: String,
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
          !selector(parser, strict).accept(version)
        } else {
          dynamicRequiredBounds && excludedByDynamicRequired(constraint.requiredVersion, version)
        }
      if (outOfBound) {
        false
      } else {
        constraint.rejectedVersions.none { selector(parser, it).accept(version) }
      }
    } catch (e: Exception) {
      true
    } catch (e: LinkageError) {
      true
    }
  }

  fun withoutExactStrictly(constraint: VersionConstraint): VersionConstraint? =
    try {
      val strict = constraint.strictVersion
      if (strict.isEmpty() || isSelectorText(strict)) {
        constraint
      } else {
        DefaultImmutableVersionConstraint.of(
          constraint.preferredVersion,
          constraint.requiredVersion,
          "",
          constraint.rejectedVersions,
        )
      }
    } catch (e: LinkageError) {
      null
    }

  /**
   * Whether the text is a selector rather than a version, which is what a constraint reported with
   * no declaration beside it carries where a resolved version would sit. A range and a dynamic
   * version are selectors, and so is a single-version range such as `[2.0]`, which parses to the
   * exact selector for a different string.
   */
  private fun isSelectorText(version: String): Boolean {
    val parser = scheme
    if (parser == null || version.isEmpty()) {
      return false
    }
    return try {
      val selector = selector(parser, version)
      selector.isDynamic || selector.selector != version
    } catch (e: Exception) {
      false
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
      val selector = selector(parser, version)
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
      constraints.all { admits(it, candidate) }
    } catch (e: Exception) {
      true
    } catch (e: LinkageError) {
      true
    }
  }

  /** Whether the constraint the platform supplied admits the version, as [accepts] reads one. */
  private fun admits(
    constraint: VersionConstraint,
    version: String,
  ): Boolean {
    val parser = scheme ?: return true
    val strict = constraint.strictVersion
    val required = constraint.requiredVersion
    return (strict.isEmpty() || selector(parser, strict).accept(version)) &&
      (required.isEmpty() || selector(parser, required).accept(version)) &&
      constraint.rejectedVersions.none { selector(parser, it).accept(version) }
  }

  /**
   * Whether the candidate is an upgrade outside a bound the resolved version lies within. A bound
   * the resolved version already lies outside is not applied, which is reached most often by a
   * transitive requirement pushing the selection past a platform; the other bounds on the module
   * still are. A constraint reported with no declaration beside it carries its selector where a
   * resolved version would sit, so the declared bound applies to every candidate there.
   */
  fun isUpgradeOutOfBound(
    constraint: VersionConstraint?,
    platformConstraints: List<VersionConstraint>,
    currentVersion: String,
    candidate: String,
    dynamicRequiredBounds: Boolean,
  ): Boolean =
    try {
      isNewer(currentVersion, candidate) &&
        (
          (
            (isSelectorText(currentVersion) || admitsDeclared(constraint, currentVersion, dynamicRequiredBounds)) &&
              !admitsDeclared(constraint, candidate, dynamicRequiredBounds)
          ) ||
            platformConstraints.any { admits(it, currentVersion) && !admits(it, candidate) }
        )
    } catch (e: Exception) {
      false
    } catch (e: LinkageError) {
      false
    }

  /**
   * Whether the candidate is newer than the version resolved for the module, ordered the way
   * dependency resolution orders versions, since a candidate that is not an upgrade is not one to
   * leave out. An unreadable version counts as newer, so the bound still applies as it did before.
   *
   * Compared rather than parsed as a selector, since a version is not a selector: a resolved
   * version containing a bracket or a comma parses as an exact selector matching nothing, which
   * would silently answer no for every candidate.
   */
  fun isNewer(
    currentVersion: String,
    candidate: String,
  ): Boolean {
    val order = comparator
    if (order == null || currentVersion.isEmpty()) {
      return true
    }
    return try {
      isSelectorText(currentVersion) || order.compare(candidate, currentVersion) > 0
    } catch (e: Exception) {
      true
    } catch (e: LinkageError) {
      true
    }
  }
}
