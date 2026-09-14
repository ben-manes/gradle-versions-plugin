package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.resolutionstrategy.CollectingComponentSelectionRules
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentFilter
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.RecordedComponentSelection
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.VersionConstraint
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.logging.Logger
import org.gradle.api.specs.Spec

/**
 * Replays the aggregating build's own component-selection rules over each row's recorded
 * candidates, starting at the row's baked verdict (the first-accept walk's answer) and walking
 * older, never newer. The baked verdict is the ceiling: the revision filter, the
 * configuration-level selection rules and the `force`/`eachDependency` effects that only the
 * producing build applies are already folded into it, and none of them can be replayed here. Every
 * including build's `dependencyUpdates` task applies its own rules to the rows beneath it, so an
 * outer build's `rejectVersionIf` is applied to a row merged in from an included build, not only to
 * the rows resolved there.
 *
 * The report's [revision] is applied to every candidate the walk reaches below the ceiling, as
 * [VersionStability] over the version string, since a record has no metadata for the status.
 *
 * A row moved below its verdict shows a version that satisfied both builds' rules and that no
 * resolution proved usable, unlike the version accepted in the producing build, which was resolved
 * there: the candidates come from a repository listing, and a version can pass version selection
 * and still fail variant selection.
 */
internal class ReportRules(
  resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?,
  private val logger: Logger,
  private val revision: String,
  private val checksPreReleases: Boolean,
  preReleaseVersionIf: Spec<String>?,
  exemptFromBuiltInChecksIf: ComponentFilter?,
) {
  private val collector = CollectingComponentSelectionRules()
  private val currentHolder = mutableMapOf<Coordinate.Key, Coordinate>()

  private val versionComparator = VersionMapping.versionComparator()

  /** The report's pre-release check, the built-in markers plus the convention added in its build. */
  private val isPreRelease: (String) -> Boolean = VersionStability.withConvention(preReleaseVersionIf)

  /** Whether a candidate is exempt from the report's built-in checks; nothing is unless configured. */
  private val isExempt: (ComponentSelectionWithCurrent) -> Boolean =
    if (exemptFromBuiltInChecksIf == null) {
      { false }
    } else {
      { current -> exemptFromBuiltInChecksIf.reject(current) }
    }

  /**
   * Whether any rule is configured on this report. The action is executed once here rather than
   * once per pass, so a rule with a side effect runs it no more often here than a producer does for
   * one resolution of a configuration.
   */
  private val hasRules: Boolean =
    if (resolutionStrategy == null) {
      false
    } else {
      try {
        resolutionStrategy.execute(
          ResolutionStrategyWithCurrent(collector, currentHolder, deprecatedBoundWarning(logger)),
        )
        !collector.isEmpty
      } catch (e: Exception) {
        // Each producer already applied this same resolutionStrategy per configuration and, on the
        // same throw, caught it there and recorded a skipped configuration (see Aggregation.kt), so
        // failing the whole task on top of that already-reported skip would help nobody. Reported
        // rather than swallowed: a strategy that throws only here, such as a serialized closure
        // reading a build script, would otherwise leave every row undecided with nothing printed.
        reportUnapplied(e)
        false
      }
    }

  /**
   * Warns that every row is left as its producer reported it, printing the first line of the throw
   * alone, as a skipped configuration from the same throw is, so a multi-line cause is not printed
   * in full.
   */
  private fun reportUnapplied(e: Exception) {
    logger.warn(
      "Every dependency is left as the build that resolved it reported it: applying this " +
        "report's component selection rules failed with " +
        e.message.orEmpty().lineSequence().first(),
    )
  }

  /**
   * Returns [statuses] with each resolved row's `latestVersion` capped to the newest candidate the
   * aggregating build's rules accept, reading the candidates from the partial for the row's
   * `projectPath`. A row absent from its partial's candidates (a `none`-version row, a file
   * dependency, an offline run, or a v1/v2 partial from an older release) is left at its baked
   * verdict.
   */
  fun applyTo(
    statuses: List<PartialStatus>,
    candidatesByProjectPath: Map<String, List<String>>,
  ): List<PartialStatus> {
    if (!hasRules && !checksPreReleases) {
      return statuses
    }
    val versionsByProjectPath = candidatesByProjectPath.mapValues { (_, candidates) -> versionsByModule(candidates) }
    return try {
      statuses.map { status -> applyToRow(status, versionsByProjectPath) }
    } catch (e: Exception) {
      // A rule body throws where registering it did not: a closure serialized into the
      // configuration cache without the build script it reads hits the missing method only once a
      // candidate reaches it. Every row falls back rather than the one that threw, so the report is
      // one build's answer throughout rather than a mix of rechecked and undecided rows.
      reportUnapplied(e)
      statuses
    }
  }

  private fun applyToRow(
    status: PartialStatus,
    versionsByProjectPath: Map<String, Map<String, List<String>>>,
  ): PartialStatus {
    if (status.unresolved != null) {
      return status
    }
    val current = holdCurrent(status)
    // Read after the holder is filled, since a rule reads the version in use through it.
    val rules = collector.rulesFor(status.group, status.name)
    return keptTierSteps(applyToVerdict(status, current, rules, versionsByProjectPath), current, rules)
  }

  /**
   * Returns [status] with the tier steps its producer recorded left out where this report would not
   * print them: newer than the row's verdict once this report's rules have moved it, rejected by
   * this report's revision or its own rules, or counted as a pre-release under its convention.
   * Nothing is looked for in place of a step left out, as a version below it satisfied this
   * report's rules alone and not the producer's.
   */
  private fun keptTierSteps(
    status: PartialStatus,
    current: Coordinate,
    rules: List<Action<in ComponentSelection>>,
  ): PartialStatus {
    if (status.patchVersion == null && status.minorVersion == null) {
      return status
    }
    if (status.unresolved != null) {
      return status.copy(patchVersion = null, minorVersion = null)
    }
    val kept = { step: String? ->
      step?.takeIf {
        versionComparator.compare(it, status.latestVersion) <= 0 &&
          keptAsStep(status, it, rules) &&
          !rejectsPreRelease(current, RecordedComponentSelection(status.group, status.name, it))
      }
    }
    val patch = kept(status.patchVersion)
    val minor = kept(status.minorVersion)
    return if (patch == status.patchVersion && minor == status.minorVersion) {
      status
    } else {
      status.copy(patchVersion = patch, minorVersion = minor)
    }
  }

  /** Returns the version in use of [status], held for the rules that read it through the wrapper. */
  private fun holdCurrent(status: PartialStatus): Coordinate {
    val current =
      Coordinate(
        status.group,
        status.name,
        status.declaredVersion,
        status.userReason,
        status.constraint?.toVersionConstraint(),
        status.platformConstraints.map { it.toVersionConstraint() },
      )
    // Carried from the partial rather than left at its default: a plugin marker's dynamic required
    // version is a bound rather than a floor, and a rule reading it here answers as it does at the
    // producer that recorded the row.
    // https://github.com/ben-manes/gradle-versions-plugin/issues/755
    current.onScriptClasspath = status.onScriptClasspath
    currentHolder.clear()
    currentHolder[Coordinate.Key(status.group, status.name)] = current
    return current
  }

  private fun applyToVerdict(
    status: PartialStatus,
    current: Coordinate,
    rules: List<Action<in ComponentSelection>>,
    versionsByProjectPath: Map<String, Map<String, List<String>>>,
  ): PartialStatus {
    // The step a producer recorded sits above that producer's verdict, which the walk below starts
    // at and never rises above, so this report's revision and its own rules are applied to it here.
    // The producer answered under its own settings, which an including build may have set otherwise.
    var preRelease: String? = status.preReleaseVersion?.takeIf { keptAsStep(status, it, rules) }

    val moduleVersions =
      status.projectPath
        ?.let { versionsByProjectPath[it] }
        ?.get("${status.group}:${status.name}")
        .orEmpty()
    val ceilingIndex = moduleVersions.indexOf(status.latestVersion)
    if (ceilingIndex < 0) {
      // Nothing to walk, so the row keeps its verdict; the step still answers to this report.
      return if (preRelease == status.preReleaseVersion) status else status.copy(preReleaseVersion = preRelease)
    }

    // Kept for the ceiling candidate alone (the first iterated below): `selectorVersion` in the
    // synthesized UnresolvedInfo is always the ceiling, so the reason reported has to be why that
    // version was rejected rather than why some older candidate further down the walk was.
    var ceilingReason: String? = null
    for (index in ceilingIndex until moduleVersions.size) {
      val version = moduleVersions[index]
      val shim = RecordedComponentSelection(status.group, status.name, version)
      // Applied at the ceiling as well as below it, and ahead of the rules, as the producer
      // applies it ahead of a build's own rules. The version a producer baked was accepted under
      // that build's check, which an included build may have switched off or extended with a
      // convention, so every merged row is checked again here.
      if (rejectsPreRelease(current, shim)) {
        if (index == ceilingIndex) {
          ceilingReason = PRE_RELEASE_REASON
        }
        if (preRelease == null && keptAsStep(status, version, rules)) {
          preRelease = version
        }
        continue
      }
      // An exemption evaluated on the metadata absent from the record answered about the record
      // rather than about the candidate, so the row is left as the build that resolved it reported it, as
      // it is for a rule that rejects the same way.
      if (shim.undecided) {
        return status
      }
      // Below the ceiling the report's revision is all a candidate can be checked against, since
      // no status is recorded. A guard rejection is never an undecided one: only the version string
      // is read, and every record has one.
      if (index > ceilingIndex && !accepted(status, version)) {
        continue
      }
      for (rule in rules) {
        if (shim.rejected || shim.undecided) break
        shim.applyRule(rule)
      }
      // A rule that rejected on the metadata absent from the record answered about the record
      // rather than the candidate, and nothing distinguishes the candidates below it from that same
      // answer, so the row is left as the build that resolved it reported it. Continuing the walk
      // would report a version this build's rules had already rejected above.
      if (shim.undecided) {
        return status
      }
      if (!shim.rejected) {
        return if (version == status.latestVersion && preRelease == status.preReleaseVersion) {
          status
        } else {
          status.copy(latestVersion = version, preReleaseVersion = preRelease)
        }
      }
      if (index == ceilingIndex) {
        ceilingReason = shim.reason
      }
    }
    return unresolvedRow(status, ceilingReason)
  }

  /** Returns [status] as a row no version satisfied, reporting why its verdict was rejected. */
  private fun unresolvedRow(
    status: PartialStatus,
    ceilingReason: String?,
  ): PartialStatus =
    status.copy(
      latestVersion = "none",
      unresolved =
        UnresolvedInfo(
          status.group,
          status.name,
          status.latestVersion,
          ceilingReason.takeUnless { it.isNullOrEmpty() }
            ?: "Rejected by the aggregating build's component selection rules",
          status.declaredVersion,
          status.userReason,
        ),
    )

  /**
   * Returns whether [version] is printed as this row's pre-release step, which it is when this
   * report's revision and its own rules both accept it. The revision is applied to every step, the
   * producer's recorded one included: a producer resolving at the `integration` revision records a
   * snapshot-grade step that a report at `release` or `milestone` leaves out. The rules are applied
   * here rather than in the walk above, which reaches a pre-release candidate only to skip it, and
   * applied to a shim of their own so that the walk's verdict for the same candidate is not
   * overwritten. A rule reading the metadata absent from the record leaves the step alone, as an
   * undecided candidate is left alone everywhere else.
   */
  private fun keptAsStep(
    status: PartialStatus,
    version: String,
    rules: List<Action<in ComponentSelection>>,
  ): Boolean {
    if (!accepted(status, version)) {
      return false
    }
    if (rules.isEmpty()) {
      return true
    }
    val shim = RecordedComponentSelection(status.group, status.name, version)
    for (rule in rules) {
      if (shim.rejected || shim.undecided) break
      shim.applyRule(rule)
    }
    return !shim.rejected || shim.undecided
  }

  /**
   * Returns whether the report's `rejectPreReleases` leaves this candidate out, read
   * through the same wrapper a rule reads it through so that `isPreRelease` and an
   * `exemptFromBuiltInChecksIf` predicate answer here as they do at a producer.
   */
  private fun rejectsPreRelease(
    current: Coordinate,
    shim: RecordedComponentSelection,
  ): Boolean {
    if (!checksPreReleases) {
      return false
    }
    val selection =
      ComponentSelectionWithCurrent(
        shim,
        current.version,
        current.versionConstraint,
        current.platformVersionConstraints,
        current.onScriptClasspath,
        {},
        isPreRelease,
      )
    if (!selection.isPreRelease()) {
      return false
    }
    // Read through the record rather than called, so an exemption that reads the metadata absent
    // from the record marks the candidate undecided instead of answering. The caller leaves such a
    // row alone; leaving it out here on an answer no predicate could reach would drop an
    // upgrade the producer reported.
    val exempt = shim.evaluate { isExempt(selection) }
    return !shim.undecided && !exempt
  }

  /**
   * Returns whether the report's revision accepts [version] for [status], exempting the version
   * already declared in the build so that a row is never held back from the release it is on.
   * https://github.com/ben-manes/gradle-versions-plugin/issues/475
   */
  private fun accepted(
    status: PartialStatus,
    version: String,
  ): Boolean = version == status.declaredVersion || VersionStability.accepts(revision, version)

  /** Rebuilds the constraint the four serialized strings captured; `branch` is not serialized. */
  private fun ConstraintInfo.toVersionConstraint(): VersionConstraint =
    DefaultImmutableVersionConstraint(preferred, required, strict, rejected, "")

  /**
   * Returns the recorded candidates of one project as the versions of each module, newest first.
   * Partitioned once for the whole report rather than per row, which would rescan and resort every
   * candidate the project recorded for each of its rows.
   *
   * Sorted rather than read as recorded: a candidate is recorded in the order its repository lists
   * it, so a module found in more than one repository is recorded newest-first per repository
   * rather than newest-first overall, and the verdict can trail candidates older than itself. The
   * walk in [applyToRow] reads position as age, so the order has to match.
   */
  private fun versionsByModule(candidates: List<String>): Map<String, List<String>> {
    val byModule = mutableMapOf<String, MutableList<String>>()
    for (candidate in candidates) {
      val group = candidate.indexOf(':')
      if (group < 0) continue
      val name = candidate.indexOf(':', group + 1)
      if (name < 0) continue
      byModule
        .getOrPut(candidate.substring(0, name)) { mutableListOf() }
        .add(candidate.substring(name + 1))
    }
    val newestFirst = VersionMapping.versionComparator().reversed()
    return byModule.mapValues { (_, versions) -> versions.sortedWith(newestFirst) }
  }

  private companion object {
    /** The reason a producer prints for the same rejection, so a row reads alike either way. */
    const val PRE_RELEASE_REASON = "Pre-release rejected by rejectPreReleases"
  }
}
