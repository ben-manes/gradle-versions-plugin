package com.github.benmanes.gradle.versions.updates

/**
 * Matches the version strings of pre-releases.
 *
 * The check is for a pre-release marker rather than for a stable pattern. A stable pattern has to
 * list every qualifier a project may put on a release, and that set is open-ended: `10.2.0.jre11`,
 * `0.10.0-hadoop1`, `1.1.33.android`, `9.2-1002-jdbc4`, `0.4-groovy-1.6`, `1.1.17.SP2` and
 * `2.0.10.graal` are all releases, and none of them matches the pattern recommended in the README. A
 * pattern of that kind hides a release, and the update is then never reported.
 *
 * The set of pre-release markers is small by comparison, so this matches those and leaves
 * everything else alone. A version with a qualifier not in the list is passed through, so a miss
 * leaves a pre-release in the report rather than hiding a release.
 *
 * https://github.com/ben-manes/gradle-versions-plugin/issues/440
 */
internal object VersionStability {
  /**
   * The markers, each of which has to stand on its own rather than appear inside a longer word, so
   * that `1.0-mysql` is not a milestone and `1.0-march` is not a release candidate. `ea` is early
   * access, and `pr` is how Jackson abbreviates `pre` in `2.10.0.pr1`.
   *
   * `incubating` is deliberately absent. The Apache Incubator requires it in the version of a
   * podling's *released* artifacts, so matching it would leave a release out.
   */
  private val MARKERS =
    listOf(
      "alpha", "beta", "canary", "candidate", "cr", "dev", "draft", "ea", "eap", "experimental",
      "milestone", "nightly", "pr", "pre", "preview", "rc", "snap", "snapshot", "unstable",
    )

  // A marker may follow a separator or a digit, as in `3.2.0rc2`. The one-letter `m` of `3.0-M1`
  // is the exception: after a digit it is a letter suffix, as in the `1.1.1m-1.5.7` of a
  // repackaged OpenSSL, so it is only a marker after a separator.
  private val MARKER =
    Regex(
      "(?:(?:^|[-._]|(?<=\\d))(?:${MARKERS.joinToString("|")})|(?:^|[-._])m)(?=[-._]|\\d|$)",
      RegexOption.IGNORE_CASE,
    )

  // Maven's timestamped snapshot, a published convention rather than a guess. The `-SNAPSHOT`
  // spelling is a marker above.
  // https://maven.apache.org/guides/getting-started/index.html#what-is-a-snapshot-version
  private val TIMESTAMPED_SNAPSHOT = Regex("""-\d{8}\.\d{6}-\d+$""")

  // A version ending in a commit hash was published by a CI job rather than released by a
  // project. At least one `a-f` is required so that a build number spelled in digits alone, such
  // as `2.0.0-1234567`, is left alone: those are not hashes and some of them are releases.
  private val COMMIT_HASH =
    Regex("""[-._](?=[0-9a-f]*[a-f])[0-9a-f]{7,}$""", RegexOption.IGNORE_CASE)

  /**
   * Returns whether [version] is a pre-release, by a marker in the list above, by Maven's
   * timestamped snapshot form, or by a trailing commit hash. A convention not in the list goes in a
   * `rejectVersionIf` filter, which is applied in addition to this check.
   */
  @JvmStatic
  fun isPreRelease(version: String): Boolean {
    // Under semantic versioning build metadata has no precedence, so a release may end in a `+`
    // and, say, the hash of the commit it was built from. Only the part before the `+` is checked.
    // https://semver.org/#spec-item-10
    val qualified = version.substringBefore('+')
    return MARKER.containsMatchIn(qualified) ||
      TIMESTAMPED_SNAPSHOT.containsMatchIn(qualified) ||
      COMMIT_HASH.containsMatchIn(qualified)
  }

  /**
   * Returns whether an upgrade from [currentVersion] to [candidateVersion] would trade a release
   * for a pre-release. The candidate is only rejected when the current version is a release, so
   * that the next release candidate is still reported to a build on one.
   */
  @JvmStatic
  fun isLessStable(
    candidateVersion: String,
    currentVersion: String,
  ): Boolean = isPreRelease(candidateVersion) && !isPreRelease(currentVersion)
}
