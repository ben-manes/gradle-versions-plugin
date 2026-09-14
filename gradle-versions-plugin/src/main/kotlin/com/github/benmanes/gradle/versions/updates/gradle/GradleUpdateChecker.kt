package com.github.benmanes.gradle.versions.updates.gradle

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okio.buffer
import okio.source
import org.gradle.util.GradleVersion
import java.net.HttpURLConnection
import java.net.URI
import java.util.EnumMap

/**
 * Facade class that provides information about the running gradle version and the latest versions
 * of the different gradle release channels. The information is queried from the official gradle api
 * via HTTPS during object construction.
 *
 * @property enabled The check for Gradle updates was enabled and, if so, the versions were fetched.
 * @see GradleReleaseChannel
 */
class GradleUpdateChecker internal constructor(
  val enabled: Boolean,
  private val gradleVersionsApiBaseUrl: String,
  /** The connect and read timeout of each request, in milliseconds. */
  timeoutMillis: Int,
) {
  constructor(
    enabled: Boolean = true,
    gradleVersionsApiBaseUrl: String,
  ) : this(enabled, gradleVersionsApiBaseUrl, CLIENT_TIME_OUT)

  init {
    if (enabled) {
      fetch(gradleVersionsApiBaseUrl, timeoutMillis)
    }
  }

  /**
   * @return An instance of [ReleaseStatus.Available] containing a [GradleVersion]
   * representing the version of the running gradle instance
   */
  fun getRunningGradleVersion(): ReleaseStatus.Available {
    return ReleaseStatus.Available(GradleVersion.current())
  }

  /**
   * @return An instance of [ReleaseStatus] explaining the update check for the latest version
   * on the "current" gradle release channel.
   */
  fun getCurrentGradleVersion(): ReleaseStatus? {
    return cacheMap[GradleReleaseChannel.CURRENT]
  }

  /**
   * @return An instance of [ReleaseStatus] explaining the update check for the latest version
   * on the "release-candidate" gradle release channel.
   */
  fun getReleaseCandidateGradleVersion(): ReleaseStatus? {
    return cacheMap[GradleReleaseChannel.RELEASE_CANDIDATE]
  }

  /**
   * @return An instance of [ReleaseStatus] explaining the update check for the latest version
   * on the "nightly" gradle release channel.
   */
  fun getNightlyGradleVersion(): ReleaseStatus? {
    return cacheMap[GradleReleaseChannel.NIGHTLY]
  }

  /**
   * Abstract class representing the possible states of a release channel after an update check.
   */
  sealed class ReleaseStatus {
    /**
     * Class representing an available release. Holds the release version in the
     * form of a [GradleVersion].
     */
    class Available(val gradleVersion: GradleVersion) : ReleaseStatus()

    /**
     * Class representing a release channel without any releases. This may be the case with
     * pre-release channels after an update has been released to general availability.
     */
    object Unavailable : ReleaseStatus()

    /**
     * Class representing a failure during update checking.
     */
    class Failure(val reason: String) : ReleaseStatus()
  }

  companion object {
    private val cacheMap =
      EnumMap<GradleReleaseChannel, ReleaseStatus>(
        GradleReleaseChannel::class.java,
      )
    private const val CLIENT_TIME_OUT = 15_000
    private val versionSite =
      Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(VersionSite::class.java)

    /** Represents the JSON from [gradleVersionsApiBaseUrl] */
    private class VersionSite {
      var version: String? = null
    }

    private fun fetch(
      gradleVersionsApiBaseUrl: String,
      timeoutMillis: Int,
    ) {
      for (it in GradleReleaseChannel.values()) {
        try {
          val connection = URI(gradleVersionsApiBaseUrl + it.id).toURL().openConnection() as HttpURLConnection
          connection.connectTimeout = timeoutMillis
          connection.readTimeout = timeoutMillis
          val version =
            try {
              connection.inputStream.source().buffer().use { body -> versionSite.fromJson(body)?.version.orEmpty() }
            } finally {
              connection.disconnect()
            }
          if (version.isNotEmpty()) {
            cacheMap[it] = ReleaseStatus.Available(GradleVersion.version(version))
          } else {
            cacheMap[it] = ReleaseStatus.Unavailable
          }
        } catch (e: Exception) {
          cacheMap[it] = ReleaseStatus.Failure(e.message.orEmpty())
        }
      }
    }
  }
}
