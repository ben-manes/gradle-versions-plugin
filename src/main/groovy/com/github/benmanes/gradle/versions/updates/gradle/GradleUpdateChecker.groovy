package com.github.benmanes.gradle.versions.updates.gradle

import static java.util.concurrent.TimeUnit.SECONDS

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import javax.annotation.Nullable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.gradle.util.GradleVersion

/**
 * Facade class that provides information about the running gradle version and the latest versions of the different
 * gradle release channels. The information is queried from the official gradle api via HTTPS during object construction.
 *
 * @see GradleReleaseChannel
 */
@CompileStatic
class GradleUpdateChecker {
  private static final Map<GradleReleaseChannel, ReleaseStatus> cacheMap = new EnumMap<>(
    GradleReleaseChannel.class)
  private static final String API_BASE_URL = "https://services.gradle.org/versions/"
  private static final long CLIENT_TIME_OUT = 15_000L // milliseconds
  private static final OkHttpClient client = new OkHttpClient.Builder()
    .connectTimeout(CLIENT_TIME_OUT, SECONDS)
    .writeTimeout(CLIENT_TIME_OUT, SECONDS)
    .readTimeout(CLIENT_TIME_OUT, SECONDS)
    .build()
  private static final Moshi moshi = new Moshi.Builder()
    .addLast(new KotlinJsonAdapterFactory())
    .build()
  // TODO: convert this to Map<String?, String?> and remove kotlin-reflect/moshi-kotlin?
  private static final class VersionSite {
    @Nullable
    String version = null
  }
  private final boolean enabled

  GradleUpdateChecker(boolean enabled) {
    this.enabled = enabled
    if (enabled) {
      fetch()
    }
  }

  private static void fetch() {
    for (it in GradleReleaseChannel.values()) {
      Response response
      try {
        response = client.newCall(
          new Request.Builder()
            .url(API_BASE_URL + it.id)
            .build()
        ).execute()
        VersionSite version = moshi.adapter(VersionSite.class).fromJson(response.body().source())
        if (version.version != null) {
          cacheMap[it] = new ReleaseStatus.Available(GradleVersion.version(version.version ?: ""))
        } else {
          cacheMap[it] = new ReleaseStatus.Unavailable()
        }
      } catch (Exception e) {
        cacheMap[it] = new ReleaseStatus.Failure(e.message ?: "")
      } finally {
        if (response != null) {
          response.close()
        }
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

  /** @return if the check for Gradle updates was enabled and, if so, the versions were fetched.   */
  boolean isEnabled() {
    return enabled
  }

  /**
   * @return An instance of {@link ReleaseStatus} explaining the update check for the latest version on the "current"
   * gradle release channel.
   */
  static ReleaseStatus getCurrentGradleVersion() {
    return cacheMap.get(GradleReleaseChannel.CURRENT)
  }

  /**
   * @return An instance of {@link ReleaseStatus} explaining the update check for the latest version on the
   * "release-candidate" gradle release channel.
   */
  static ReleaseStatus getReleaseCandidateGradleVersion() {
    return cacheMap.get(GradleReleaseChannel.RELEASE_CANDIDATE)
  }

  /**
   * @return An instance of {@link ReleaseStatus} explaining the update check for the latest version on the "nightly"
   * gradle release channel.
   */
  static ReleaseStatus getNightlyGradleVersion() {
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

      private Failure(String reason) {
        this.reason = reason
      }
    }
  }
}
