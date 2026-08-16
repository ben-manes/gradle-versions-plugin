package com.github.benmanes.gradle.versions.updates

/**
 * Recognizes the version strings that name a pre-release.
 *
 * This answers "is this a pre-release?" rather than "is this stable?", and the direction is the
 * whole point. A stable-version test has to recognize every way a project can qualify a release it
 * did ship, and that set is open-ended: `10.2.0.jre11`, `0.10.0-hadoop1`, `1.1.33.android`,
 * `9.2-1002-jdbc4`, `0.4-groovy-1.6`, `1.1.17.SP2`, `2.0.10.graal` are all releases whose strings
 * no pattern anticipated. A test built that way hides them, which is the failure a build cannot
 * afford: an update it never learns about.
 *
 * A pre-release marker set is closed and small by comparison, so this recognizes those and leaves
 * everything else alone. Being wrong here can only leave a pre-release on offer, which is what a
 * build sees today with no filter at all.
 *
 * https://github.com/ben-manes/gradle-versions-plugin/issues/440
 */
object VersionStability {
  /**
   * The markers, each of which has to stand on its own rather than appear inside a longer word, so
   * that `3.0-GAMMA` is a milestone and `1.0-legacy` is not a `GA` release.
   */
  private val MARKERS =
    listOf(
      "alpha", "beta", "canary", "candidate", "cr", "dev", "draft", "eap", "experimental",
      "incubating", "m", "milestone", "nightly", "pre", "preview", "rc", "snap", "snapshot",
      "unstable",
    )

  private val MARKER =
    Regex(
      "(?:^|[-._+]|(?<=\\d))(${MARKERS.joinToString("|")})(?=[-._+]|\\d|$)",
      RegexOption.IGNORE_CASE,
    )

  // Jackson publishes `2.10.0.pr1`, abbreviating the same word `pre` spells.
  private val ABBREVIATED_PRE = Regex("""[-._+]pr\d+$""", RegexOption.IGNORE_CASE)

  // Maven's own snapshot conventions, which are a published convention rather than a guess.
  // https://maven.apache.org/guides/getting-started/index.html#what-is-a-snapshot-version
  private val TIMESTAMPED_SNAPSHOT = Regex("""-\d{8}\.\d{6}-\d+$""")

  // A version ending in a commit hash is something a CI job published, not something a project
  // released. At least one `a-f` is required so that a build number spelled in digits alone, such
  // as `2.0.0-1234567`, is left alone: those are not hashes and some of them are releases.
  private val COMMIT_HASH =
    Regex("""[-._+](?=[0-9a-f]*[a-f])[0-9a-f]{7,}$""", RegexOption.IGNORE_CASE)

  private val EARLY_ACCESS = Regex("""(?:^|[-._+])ea(?=$|[-._+]|\d{1,3}$)""", RegexOption.IGNORE_CASE)

  /**
   * Returns whether [version] names a pre-release, by a marker this recognizes or by a trailing
   * commit hash. A build whose dependencies use a convention this does not carry names it in a
   * `rejectVersionIf` filter, which composes with this one rather than replacing it.
   */
  @JvmStatic
  fun isPreRelease(version: String): Boolean {
    if (isSnapshot(version)) return true
    if (MARKER.containsMatchIn(version) || ABBREVIATED_PRE.containsMatchIn(version)) return true
    return COMMIT_HASH.containsMatchIn(version) || EARLY_ACCESS.containsMatchIn(version)
  }

  /**
   * Returns whether upgrading from [currentVersion] to [candidateVersion] would trade a release for
   * a pre-release, which is the question a report actually has to answer. A build already sitting on
   * a release candidate still wants to hear about the next one, so the candidate is only withheld
   * when the version in hand is a release.
   */
  @JvmStatic
  fun isLessStable(
    candidateVersion: String,
    currentVersion: String,
  ): Boolean = isPreRelease(candidateVersion) && !isPreRelease(currentVersion)

  /** Returns whether [version] names a snapshot, by either of Maven's two spellings. */
  @JvmStatic
  fun isSnapshot(version: String): Boolean =
    version.contains("SNAPSHOT", ignoreCase = true) || TIMESTAMPED_SNAPSHOT.containsMatchIn(version)
}
