package com.github.benmanes.gradle.versions.reporter

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
