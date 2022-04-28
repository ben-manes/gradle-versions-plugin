package com.github.benmanes.gradle.versions.updates

import org.gradle.api.artifacts.UnresolvedDependency

/**
 * The version status of a dependency.
 *
 * The <tt>latestVersion</tt> is set if the dependency was successfully resolved, otherwise the
 * <tt>unresolved</tt> contains the exception that caused the resolution to fail.
 */
class DependencyStatus {
  val coordinate: Coordinate
  val latestVersion: String
  val unresolved: UnresolvedDependency?
  val projectUrl: String?

  constructor(coordinate: Coordinate, latestVersion: String, projectUrl: String?) {
    this.coordinate = coordinate
    this.latestVersion = latestVersion
    this.projectUrl = projectUrl
    this.unresolved = null
  }

  constructor(coordinate: Coordinate, unresolved: UnresolvedDependency?) {
    this.coordinate = coordinate
    this.unresolved = unresolved
    latestVersion = "none"
    projectUrl = null
  }

  fun getLatestCoordinate(): Coordinate {
    return Coordinate(
      coordinate.groupId,
      coordinate.artifactId,
      latestVersion,
      coordinate.userReason
    )
  }
}
