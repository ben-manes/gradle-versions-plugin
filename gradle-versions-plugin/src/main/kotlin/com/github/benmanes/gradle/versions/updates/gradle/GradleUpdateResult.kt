package com.github.benmanes.gradle.versions.updates.gradle

import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateChecker.ReleaseStatus
import com.squareup.moshi.JsonClass
import org.gradle.util.GradleVersion

/**
 * Holder class for gradle update results of a specific release channel (or the running version).
 * Used for reporting & serialization to JSON/XML.
 */
@JsonClass(generateAdapter = true)
class GradleUpdateResult(
  enabled: Boolean = false,
  running: ReleaseStatus.Available? = null,
  release: ReleaseStatus? = null,
) : Comparable<GradleUpdateResult> {
  /**
   * The version available on the release channel represented by this object.
   */
  var version: String

  /**
   * Indicates whether the [version] is an update with respect to the currently running gradle
   * version.
   */
  var isUpdateAvailable: Boolean

  /**
   * Indicates whether the check for Gradle updates on this release channel failed.
   */
  var isFailure: Boolean

  /**
   * An explanatory field on how to interpret the results. Useful when [version] is not set to a
   * valid value.
   */
  var reason: String

  init {
    if (!enabled) {
      version = ""
      isUpdateAvailable = false
      isFailure = false
      reason = "update check disabled"
    } else if (release is ReleaseStatus.Available) {
      version = release.gradleVersion.version
      isUpdateAvailable = release.gradleVersion > running!!.gradleVersion
      isFailure = false
      reason = "" // empty string so the field is serialized
    } else if (release is ReleaseStatus.Unavailable) {
      version = "" // empty string so the field is serialized
      isUpdateAvailable = false
      isFailure = false
      reason = "update check succeeded: no release available"
    } else if (release is ReleaseStatus.Failure) {
      version = "" // empty string so the field is serialized
      isUpdateAvailable = false
      isFailure = true
      reason = release.reason
    } else {
      throw IllegalStateException(
        "ReleaseStatus subtype [" + release!!.javaClass + "] not yet implemented",
      )
    }
  }

  /**
   * Compares two instances of [GradleUpdateResult].
   *
   * @return an integer as specified by [Comparable.compareTo]
   * @throws IllegalArgumentException when one of the [GradleUpdateResult]s does not represent
   * a valid [GradleVersion]. This may be the case when a [GradleUpdateResult]
   * represents a failure.
   */
  override fun compareTo(other: GradleUpdateResult): Int {
    return comparator.compare(this, other)
  }

  companion object {
    /**
     * Comparator that compares two instances of [GradleUpdateResult] by comparing the
     * [GradleVersion] they represent
     */
    private val comparator =
      Comparator.comparing { gradleUpdateResult: GradleUpdateResult ->
        GradleVersion.version(gradleUpdateResult.version)
      }
  }
}
