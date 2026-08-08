package com.github.benmanes.gradle.versions.reporter.result

/** A configuration whose dependencies could not be inspected because applying the resolutionStrategy to it failed. */
data class SkippedConfiguration(
  val project: String,
  val name: String,
  val reason: String,
) : Comparable<SkippedConfiguration> {
  override fun compareTo(other: SkippedConfiguration): Int {
    return compareValuesBy(this, other, { it.project }, { it.name }, { it.reason })
  }
}

/**
 * A group of skipped configurations.
 *
 * @property count The number of skipped configurations in this group.
 * @property configurations The skipped configurations that belong to this group, kept as a list
 * rather than a set so that two projects skipping an identically named configuration for the same
 * reason both survive rather than collapsing into one.
 */
data class SkippedConfigurationsGroup(
  val count: Int,
  val configurations: List<SkippedConfiguration> = emptyList(),
)
