package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.DependencyOutdated

/**
 * Returns the row's version steps, from the version in use through the newest patch and the newest
 * minor to the newest the resolution accepted and the pre-release step, each kept only where it is
 * newer than the one before it.
 */
internal fun laterSteps(
  dependency: DependencyOutdated,
  revision: String,
  versionComparator: Comparator<String>,
): List<String> {
  val available = dependency.available
  return laterSteps(
    listOf(dependency.version, available.patch, available.minor, available[revision], available.preRelease),
    versionComparator,
  )
}

/**
 * Returns the steps of a row's version path in order, each kept only where it is newer than the
 * one kept before it, as the Gradle row's release candidate is. Compared rather than deduplicated,
 * since a step equal to the one before it is not the only one with nothing to add: a step below it
 * has nothing to add either. Null and blank steps are left out.
 */
internal fun laterSteps(
  steps: List<String?>,
  versionComparator: Comparator<String>,
): List<String> {
  val kept = mutableListOf<String>()
  for (step in steps) {
    if (step.isNullOrEmpty()) continue
    if (kept.isEmpty() || versionComparator.compare(kept.last(), step) < 0) {
      kept.add(step)
    }
  }
  return kept
}
