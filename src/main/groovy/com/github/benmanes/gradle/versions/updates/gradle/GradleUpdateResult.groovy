package com.github.benmanes.gradle.versions.updates.gradle

import groovy.transform.CompileStatic
import javax.annotation.Nullable
import org.gradle.util.GradleVersion

/**
 * Holder class for gradle update results of a specific release channel (or the running version).
 * Used for reporting & serialization to JSON/XML
 */
@CompileStatic
class GradleUpdateResult implements Comparable<GradleUpdateResult> {

  /**
   * Comparator that compares two instances of {@link GradleUpdateResult} by comparing the {@link GradleVersion} they
   * represent
   */
  private static final Comparator comparator = Comparator.comparing {
    GradleUpdateResult gradleUpdateResult -> GradleVersion.version(gradleUpdateResult.version)
  }

  /**
   * The version available on the release channel represented by this object.
   */
  final String version
  /**
   * Indicates whether the {@link #version} is an update with respect to the currently running gradle version.
   */
  final boolean isUpdateAvailable
  /**
   * Indicates whether the check for Gradle updates on this release channel failed.
   */
  final boolean isFailure
  /**
   * An explanatory field on how to interpret the results. Useful when {@link #version} is not set to a valid value.
   */
  final String reason

  GradleUpdateResult(boolean enabled, @Nullable GradleUpdateChecker.ReleaseStatus.Available running,
    @Nullable GradleUpdateChecker.ReleaseStatus release) {
    if (!enabled) {
      this.version = ""
      this.isUpdateAvailable = false
      this.isFailure = false
      this.reason = "update check disabled"
    } else if (release instanceof GradleUpdateChecker.ReleaseStatus.Available) {
      this.version = release.gradleVersion.version
      this.isUpdateAvailable = release.gradleVersion > running.gradleVersion
      this.isFailure = false
      this.reason = "" // empty string so the field is serialized
    } else if (release instanceof GradleUpdateChecker.ReleaseStatus.Unavailable) {
      this.version = "" // empty string so the field is serialized
      this.isUpdateAvailable = false
      this.isFailure = false
      this.reason = "update check succeeded: no release available"
    } else if (release instanceof GradleUpdateChecker.ReleaseStatus.Failure) {
      this.version = "" // empty string so the field is serialized
      this.isUpdateAvailable = false
      this.isFailure = true
      this.reason = release.reason
    } else {
      throw new IllegalStateException(
        "ReleaseStatus subtype [" + release.class + "] not yet implemented")
    }
  }

  /**
   * Compares two instances of {@link GradleUpdateResult}.
   * @throws IllegalArgumentException when one of the {@link GradleUpdateResult}s does not represent a valid
   * {@link GradleVersion}. This may be the case when a {@link GradleUpdateResult} represents a failure.
   * @param o
   * @return an integer as specified by {@link Comparable#compareTo(java.lang.Object)}
   */
  @Override
  int compareTo(GradleUpdateResult o) {
    return comparator.compare(this, o)
  }
}
