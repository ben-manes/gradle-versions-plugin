package com.github.benmanes.gradle.versions.updates.gradle

import groovy.json.JsonException
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.gradle.util.GradleVersion

import java.util.concurrent.TimeUnit

/**
 * Facade class that provides information about the running gradle version and the latest versions of the different
 * gradle release channels. The information is queried from the official gradle api via HTTPS during object construction.
 *
 * @see GradleReleaseChannel
 */
@CompileStatic
class GradleUpdateChecker {
  private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15)
  private static final String API_BASE_URL = 'https://services.gradle.org/versions/'

  private final Map<GradleReleaseChannel, ReleaseStatus> cacheMap = new EnumMap<>(GradleReleaseChannel.class)
  private final boolean enabled

  GradleUpdateChecker(boolean enabled) {
    this.enabled = enabled
    if (enabled) {
      fetch()
    }
  }

  @CompileDynamic
  private void fetch() {
    GradleReleaseChannel.values().each {
      try {
        def versionObject = new JsonSlurper().parse(new URL(API_BASE_URL + it.id), [
          'connectTimeout': TIMEOUT_MS, 'readTimeout': TIMEOUT_MS])
        if (versionObject.version) {
          cacheMap.put(it, new ReleaseStatus.Available(GradleVersion.version(versionObject.version as String)))
        } else {
          cacheMap.put(it, new ReleaseStatus.Unavailable())
        }
      } catch (JsonException e) {
        // JsonSlurper throws JsonException for all types of parsing failures (including I/O exceptions).
        cacheMap.put(it, new ReleaseStatus.Failure(e.getMessage()))
      }
    }
  }

  /**
   * @return An instance of
   * {@link com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateChecker.ReleaseStatus.Available} containing a
   * {@link GradleVersion} representing the version of the running gradle instance
   */
  static ReleaseStatus.Available getRunningGradleVersion() {
    return new ReleaseStatus.Available(GradleVersion.current())
  }

  /** @return if the check for Gradle updates was enabled and, if so, the versions were fetched. */
  boolean isEnabled() {
    return enabled
  }

  /**
   * @return An instance of {@link ReleaseStatus} explaining the update check for the latest version on the 'current'
   * gradle release channel.
   */
  ReleaseStatus getCurrentGradleVersion() {
    return cacheMap.get(GradleReleaseChannel.CURRENT)
  }

  /**
   * @return An instance of {@link ReleaseStatus} explaining the update check for the latest version on the
   * 'release-candidate' gradle release channel.
   */
  ReleaseStatus getReleaseCandidateGradleVersion() {
    return cacheMap.get(GradleReleaseChannel.RELEASE_CANDIDATE)
  }

  /**
   * @return An instance of {@link ReleaseStatus} explaining the update check for the latest version on the 'nightly'
   * gradle release channel.
   */
  ReleaseStatus getNightlyGradleVersion() {
    return cacheMap.get(GradleReleaseChannel.NIGHTLY)
  }

  /**
   * Abstract class representing the possible states of a release channel after an update check.
   */
  @PackageScope
  static abstract class ReleaseStatus {
    /**
     * Class representing an available release. Holds the release version in the form of a {@link GradleVersion}.
     */
    @PackageScope
    static class Available extends ReleaseStatus {
      final GradleVersion gradleVersion

      private Available(GradleVersion gradleVersion) {
        this.gradleVersion = gradleVersion
      }
    }

    /**
     * Class representing a release channel without any releases. This may be the case with pre-release channels after
     * an update has been released to general availability.
     */
    @PackageScope
    static class Unavailable extends ReleaseStatus {}

    /**
     * Class representing a failure during update checking.
     */
    @PackageScope
    static class Failure extends ReleaseStatus {
      final String reason

      private Failure(reason) {
        this.reason = reason
      }
    }
  }
}
