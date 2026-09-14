package com.github.benmanes.gradle.versions.updates

import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import java.util.concurrent.ConcurrentHashMap

/** A tier of versions, by how many leading numeric parts of the declared version its members share. */
internal enum class VersionTier(val parts: Int) {
  PATCH(VersionTiers.PATCH),
  MINOR(VersionTiers.MINOR),
}

/**
 * The versions that share the leading numeric parts of a declared version: its first two for the
 * latest patch, and its first one for the latest minor version. A version is split into parts as
 * dependency resolution splits it, at `.`, `-`, `_` and `+` and between digits and letters.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/69
 */
internal object VersionTiers {
  const val PATCH = 2
  const val MINOR = 1

  private val parser = VersionParser()

  // One pattern per tier rather than one per call, since a module's selector is looked up once
  // per candidate in the tier filter.
  private val prefixes = ConcurrentHashMap<Int, Regex>()

  /**
   * Returns the prefix selector that queries the versions sharing the first [parts] numeric parts of
   * [version], or null where it starts with fewer, or is a dynamic selector itself: every version
   * `1.+` reaches is one it already selects. The prefix ends at the last of those parts rather than
   * at the separator after it, since `31.0.1-jre` is the patch of `31.0-jre`. Gradle matches it as
   * text, which is both wider and narrower than [shares]: `31.01` matches `31.0+`, and `1.1.4` does
   * not match `1.01+`, though each shares the parts. A candidate is checked with [shares] before it
   * is taken, and a version spelled with other padding or separators than the declared one is left
   * out of the tier.
   */
  fun selector(
    version: String,
    parts: Int,
  ): String? {
    if (isDynamic(version) || numericPrefix(version).size < parts) {
      return null
    }
    val pattern = prefixes.getOrPut(parts) { Regex("^\\d+(?:[._+-]\\d+){${parts - 1}}") }
    val prefix = pattern.find(version)?.value ?: return null
    return "$prefix+"
  }

  /** Returns whether [candidate] shares the first [parts] numeric parts of [version]. */
  fun shares(
    candidate: String,
    version: String,
    parts: Int,
  ): Boolean {
    val declared = numericPrefix(version)
    val numbers = numericPrefix(candidate)
    return declared.size >= parts && numbers.size >= parts && numbers.subList(0, parts) == declared.subList(0, parts)
  }

  /**
   * Returns whether [version] is a selector rather than a version, in the forms Gradle's selector
   * scheme parses as dynamic: a `+` prefix such as `1.+`, `latest.release`, or a range.
   */
  private fun isDynamic(version: String): Boolean =
    version.endsWith("+") || version.startsWith("latest.") || version.firstOrNull() in RANGE_STARTS

  /** Returns the numeric parts [version] starts with, empty where the first part is not a number. */
  private fun numericPrefix(version: String): List<Long> = parser.transform(version).numericParts.takeWhile { it != null }.map { it!! }

  private val RANGE_STARTS = setOf('[', '(', ']')
}
