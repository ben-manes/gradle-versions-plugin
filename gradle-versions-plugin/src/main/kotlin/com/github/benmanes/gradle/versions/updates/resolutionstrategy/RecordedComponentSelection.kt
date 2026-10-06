package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import org.gradle.api.Action
import org.gradle.api.artifacts.ComponentMetadata
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.util.Objects

/** A value [ModuleComponentIdentifier] over three strings, with no live Gradle component behind it. */
private class RecordedModuleComponentIdentifier(
  private val group: String,
  private val module: String,
  private val version: String,
) : ModuleComponentIdentifier,
  ModuleIdentifier {
  override fun getGroup(): String = group

  override fun getModule(): String = module

  override fun getVersion(): String = version

  override fun getModuleIdentifier(): ModuleIdentifier = this

  override fun getName(): String = module

  override fun getDisplayName(): String = "$group:$module:$version"

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is RecordedModuleComponentIdentifier) return false
    return group == other.group && module == other.module && version == other.version
  }

  override fun hashCode(): Int = Objects.hash(group, module, version)
}

/**
 * A [ComponentSelection] backed by a recorded candidate rather than a live Gradle resolution, fed
 * to the aggregating build's component-selection rules at the report.
 *
 * [getMetadata] and [getDescriptor] always answer null. The contract is nullable, and a rule that
 * rejects after reading either is taken to have answered about the absence rather than about the
 * candidate, so that rejection is not honored and the candidate is [undecided] instead. Real
 * metadata would cost a fetch per candidate, and is impossible for a merged-in row whatever the
 * cost, since the child's repositories cannot be queried from the aggregating build. No real
 * predicate was observed reading either (the README, the suite, ~15 sampled predicates, ~25
 * consumer repos, 0 issues)—a bounded negative, not proof of zero usage.
 */
internal class RecordedComponentSelection(
  group: String,
  module: String,
  version: String,
) : ComponentSelection {
  private val identifier = RecordedModuleComponentIdentifier(group, module, version)
  private var readAbsentMetadata = false

  var rejected: Boolean = false
    private set

  /** The reason passed to [reject], if a rule rejected this candidate. */
  var reason: String? = null
    private set

  /** Whether a rule rejected this candidate on the metadata or descriptor absent from the record. */
  var undecided: Boolean = false
    private set

  /**
   * Runs [rule] against this candidate, keeping its rejection only where the rule reached it
   * without reading the metadata or descriptor absent from the record.
   */
  fun applyRule(rule: Action<in ComponentSelection>) {
    readAbsentMetadata = false
    rule.execute(this)
    if (rejected && readAbsentMetadata) {
      rejected = false
      undecided = true
    }
  }

  /**
   * Returns whether [predicate] is satisfied for this candidate, marking the candidate [undecided]
   * and answering false where the predicate read the metadata or descriptor absent from the record.
   * The built-in checks read an exemption through this rather than calling it, so a predicate that
   * answers on absent metadata leaves the row as its producer reported it, as a rule does.
   */
  fun evaluate(predicate: (ComponentSelection) -> Boolean): Boolean {
    readAbsentMetadata = false
    val held = predicate(this)
    if (readAbsentMetadata) {
      undecided = true
      return false
    }
    return held
  }

  override fun getCandidate(): ModuleComponentIdentifier = identifier

  override fun getMetadata(): ComponentMetadata? {
    readAbsentMetadata = true
    return null
  }

  override fun <T : Any?> getDescriptor(clazz: Class<T>): T? {
    readAbsentMetadata = true
    return null
  }

  override fun reject(reason: String) {
    rejected = true
    this.reason = reason
  }
}
