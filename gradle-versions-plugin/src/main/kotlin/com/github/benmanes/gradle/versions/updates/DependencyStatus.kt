package com.github.benmanes.gradle.versions.updates

import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

/**
 * The version status of a dependency.
 *
 * The `latestVersion` is set if the dependency was successfully resolved, otherwise the
 * `unresolved` contains the exception that caused the resolution to fail.
 */
class DependencyStatus {
  val coordinate: Coordinate
  val latestVersion: String
  val unresolved: UnresolvedDependencyResult?
  val projectUrl: String?

  constructor(coordinate: Coordinate, latestVersion: String, projectUrl: String?) {
    this.coordinate = coordinate
    this.latestVersion = latestVersion
    this.projectUrl = projectUrl
    this.unresolved = null
  }

  constructor(coordinate: Coordinate, unresolved: UnresolvedDependencyResult?) {
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
      coordinate.userReason,
    )
  }

  /** Returns the serializable projection of this status. */
  fun toPartialStatus(): PartialStatus {
    val info =
      unresolved?.let { dependency ->
        val selector = dependency.attempted as ModuleComponentSelector
        val failure = dependency.failure
        UnresolvedInfo(
          selector.group,
          selector.module,
          selector.version,
          failure.message ?: failure.toString(),
        )
      }
    return PartialStatus(
      coordinate.groupId,
      coordinate.artifactId,
      coordinate.version,
      coordinate.userReason,
      latestVersion,
      projectUrl,
      info,
    )
  }
}
