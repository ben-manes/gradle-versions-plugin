package com.github.benmanes.gradle.versions.updates

import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import org.gradle.api.logging.Logger

/**
 * A mapping of which versions are out of date, up to date, undeclared, or exceed the latest found.
 */
class VersionMapping(private val logger: Logger, statuses: List<PartialStatus>) {
  val downgrade = sortedSetOf<Coordinate>()
  val upToDate = sortedSetOf<Coordinate>()
  val upgrade = sortedSetOf<Coordinate>()
  val undeclared = sortedSetOf<Coordinate>()
  val unresolved = sortedSetOf<Coordinate>()
  val current = sortedSetOf<Coordinate>()
  val latest = sortedSetOf<Coordinate>()
  val latestByCurrent = hashMapOf<Coordinate, Coordinate>()

  /**
   * The newest candidate left out by the pre-release check, per declared coordinate, absent where
   * none was left out. Kept beside [latestByCurrent] rather than replacing its entry, so a row with
   * both steps is reported with the newest version the resolution accepted as well as the one it
   * did not.
   */
  val preReleaseByCurrent = hashMapOf<Coordinate, String>()

  /**
   * The latest version sharing the major and minor parts of each declared coordinate, absent where
   * none is later or where the statuses that found one disagree on it. A project whose own rules
   * reject a version is not offered it through a row shared with a project that found a lower one.
   * A status that found none abstains rather than vetoing: a partial written by an older release, a
   * candidate listing that failed and a variant selection that failed all report the same null as a
   * tier with nothing in it.
   */
  val patchByCurrent = hashMapOf<Coordinate, String>()

  /** The latest version sharing the major part of each declared coordinate, on the same terms. */
  val minorByCurrent = hashMapOf<Coordinate, String>()
  private var comparator = makeVersionComparator()

  init {
    val patchVotes = hashMapOf<Coordinate, MutableSet<String>>()
    val minorVotes = hashMapOf<Coordinate, MutableSet<String>>()
    for (status in statuses) {
      current.add(status.coordinate)
      if (status.unresolved == null) {
        val latestCoordinate = status.latestCoordinate
        latest.add(latestCoordinate)
        val previous = latestByCurrent[status.coordinate]
        if (previous == null || comparator.compare(previous.version, latestCoordinate.version) < 0) {
          latestByCurrent[status.coordinate] = latestCoordinate
        }
        keepNewest(preReleaseByCurrent, status.coordinate, status.preReleaseVersion)
        status.patchVersion?.let { patchVotes.getOrPut(status.coordinate) { hashSetOf() }.add(it) }
        status.minorVersion?.let { minorVotes.getOrPut(status.coordinate) { hashSetOf() }.add(it) }
      } else {
        unresolved.add(status.coordinate)
      }
    }
    keepAgreed(patchVotes, patchByCurrent)
    keepAgreed(minorVotes, minorByCurrent)
    organize()
  }

  /** Records [version] for [coordinate] where it is newer than the one recorded for another project. */
  private fun keepNewest(
    versions: MutableMap<Coordinate, String>,
    coordinate: Coordinate,
    version: String?,
  ) {
    if (version == null) {
      return
    }
    val seen = versions[coordinate]
    if (seen == null || comparator.compare(seen, version) < 0) {
      versions[coordinate] = version
    }
  }

  /** Records the version of each coordinate that every status voting for one voted for. */
  private fun keepAgreed(
    votes: Map<Coordinate, Set<String>>,
    versions: MutableMap<Coordinate, String>,
  ) {
    for ((coordinate, voted) in votes) {
      voted.singleOrNull()?.let { versions[coordinate] = it }
    }
  }

  /** Groups the dependencies into up-to-date, upgrades available, or downgrade buckets.  */
  private fun organize() {
    for (coordinate in current) {
      // A resolution that produced a version for this exact coordinate is the one to report, even
      // when
      // another one failed on it. The failure is still recorded in the unresolved set, so both the
      // update and the resolution that could not find it are reported.
      val resolved = latestByCurrent[coordinate]
      val version = resolved?.version
      logger
        .info("Comparing dependency (current: {}, latest: {})", coordinate, version ?: "unresolved")
      if (resolved == null && unresolved.contains(coordinate)) {
        continue
      } else if (coordinate.version == "none") {
        undeclared.add(coordinate)
        continue
      }
      val result = comparator.compare(coordinate.version, version)
      if (result <= -1) {
        upgrade.add(coordinate)
      } else if (result == 0) {
        // A module whose only newer candidate is a pre-release resolves to the version already
        // declared, so the row is an upgrade by the pre-release step alone and the breadcrumb
        // prints that step in place of the middle one.
        if (preReleaseByCurrent.containsKey(coordinate)) {
          upgrade.add(coordinate)
        } else {
          upToDate.add(coordinate)
        }
      } else {
        downgrade.add(coordinate)
      }
    }
  }

  companion object {
    private fun makeVersionComparator(): Comparator<String> = versionComparator(VersionParser())

    /** Returns the comparator that orders two version strings as Gradle's resolution does. */
    internal fun versionComparator(): Comparator<String> = makeVersionComparator()

    /** Orders version strings the way dependency resolution orders them, through the given parser. */
    internal fun versionComparator(versionParser: VersionParser): Comparator<String> {
      val baseComparator = DefaultVersionComparator().asVersionComparator()
      return Comparator { string1, string2 ->
        baseComparator.compare(versionParser.transform(string1), versionParser.transform(string2))
      }
    }
  }
}
