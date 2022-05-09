package com.github.benmanes.gradle.versions.updates.gradle

/**
 * Enum class that represents the available Gradle release channels and their ids in the api url.
 */
enum class GradleReleaseChannel(val id: String) {
  CURRENT("current"),
  RELEASE_CANDIDATE("release-candidate"),
  NIGHTLY("nightly"),
}
