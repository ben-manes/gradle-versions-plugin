package com.github.benmanes.gradle.versions.updates.gradle

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.Request
import org.gradle.util.GradleVersion
import java.util.EnumMap
import java.util.concurrent.TimeUnit

/**
 * Facade class that provides information about the running gradle version and the latest versions
 * of the different gradle release channels. The information is queried from the official gradle api
 * via HTTPS during object construction.
 *
 * @property enabled The check for Gradle updates was enabled and, if so, the versions were fetched.
 * @see GradleReleaseChannel
 */
class GradleUpdateChecker(
  val enabled: Boolean = true,
  private val gradleVersionsApiBaseUrl: String,
) {
  init {
    if (enabled) {
      fetch(gradleVersionsApiBaseUrl)
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
    @JsonClass(generateAdapter = true)
    data class Available(val gradleVersion: GradleVersion) : ReleaseStatus()

    /**
     * Class representing a release channel without any releases. This may be the case with
     * pre-release channels after an update has been released to general availability.
     */
    data object Unavailable : ReleaseStatus()

    /**
     * Class representing a failure during update checking.
     */
    @JsonClass(generateAdapter = true)
    data class Failure(val reason: String) : ReleaseStatus()
  }

  companion object {
    private val cacheMap =
      EnumMap<GradleReleaseChannel, ReleaseStatus>(
        GradleReleaseChannel::class.java,
      )
    private const val CLIENT_TIME_OUT = 15_000L
    private val client: OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(CLIENT_TIME_OUT, TimeUnit.SECONDS)
        .writeTimeout(CLIENT_TIME_OUT, TimeUnit.SECONDS)
        .readTimeout(CLIENT_TIME_OUT, TimeUnit.SECONDS)
        .build()
    private val moshi =
      Moshi.Builder()
        .build()

    /** Represents the XML from [gradleVersionsApiBaseUrl] */
    @JsonClass(generateAdapter = true)
    internal class VersionSite {
      val version: String? = null
    }

    private fun fetch(gradleVersionsApiBaseUrl: String) {
      for (it in GradleReleaseChannel.values()) {
        try {
          client.newCall(
            Request.Builder()
              .url(gradleVersionsApiBaseUrl + it.id)
              .build(),
          ).execute().use { response ->
            response.body?.source()?.let { body ->
              val version = moshi.adapter(VersionSite::class.java).fromJson(body)?.version.orEmpty()
              if (version.isNotEmpty()) {
                cacheMap[it] = ReleaseStatus.Available(GradleVersion.version(version))
              } else {
                cacheMap[it] = ReleaseStatus.Unavailable
              }
            }
          }
        } catch (e: Exception) {
          cacheMap[it] = ReleaseStatus.Failure(e.message.orEmpty())
        }
      }
    }
  }
}
