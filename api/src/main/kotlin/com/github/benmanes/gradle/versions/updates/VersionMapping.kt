package com.github.benmanes.gradle.versions.updates

import org.gradle.api.Project
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import java.util.SortedSet
import java.util.TreeSet

/**
 * A mapping of which versions are out of date, up to date, undeclared, or exceed the latest found.
 */
class VersionMapping(val project: Project, statuses: Set<DependencyStatus>) {
  val downgrade: SortedSet<Coordinate> = TreeSet()
  val upToDate: SortedSet<Coordinate> = TreeSet()
  val upgrade: SortedSet<Coordinate> = TreeSet()
  val undeclared: SortedSet<Coordinate> = TreeSet()
  val unresolved: SortedSet<Coordinate> = TreeSet()
  val current: SortedSet<Coordinate> = TreeSet()
  val latest: SortedSet<Coordinate> = TreeSet()
  private var comparator = makeVersionComparator()

  init {
    for (status in statuses) {
      current.add(status.coordinate)
      if (status.unresolved == null) {
        latest.add(status.getLatestCoordinate())
      } else {
        unresolved.add(status.coordinate)
      }
    }
    organize()
  }

  /** Groups the dependencies into up-to-date, upgrades available, or downgrade buckets.  */
  private fun organize() {
    val latestByKey: Map<Coordinate.Key, Coordinate> = latest.associateBy({ it.key }, { it })
    for (coordinate in current) {
      val latestCoordinate = latestByKey[coordinate.key]
      val version = latestCoordinate?.version
      project.logger
        .info("Comparing dependency (current: {}, latest: {})", coordinate, version ?: "unresolved")
      if (unresolved.contains(coordinate)) {
        continue
      } else if (coordinate.version == "none") {
        undeclared.add(coordinate)
        continue
      }
      val result = comparator.compare(coordinate.version, version)
      if (result <= -1) {
        upgrade.add(coordinate)
      } else if (result == 0) {
        upToDate.add(coordinate)
      } else {
        downgrade.add(coordinate)
      }
    }
  }

  companion object {
    private fun makeVersionComparator(): Comparator<String> {
      val baseComparator = DefaultVersionComparator().asVersionComparator()
      val versionParser = VersionParser()
      return Comparator { string1, string2 ->
        baseComparator.compare(versionParser.transform(string1), versionParser.transform(string2))
      }
    }
  }
}
