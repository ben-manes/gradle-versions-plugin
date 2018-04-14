package com.github.benmanes.gradle.versions.updates.gradle

/**
 * Enum class that represents the available Gradle release channels and their ids in the api url
 */
enum GradleReleaseChannel {
  CURRENT('current'),
  RELEASE_CANDIDATE('release-candidate'),
  NIGHTLY('nightly')

  final String id
  private GradleReleaseChannel(String id) {
    this.id = id
  }

}
