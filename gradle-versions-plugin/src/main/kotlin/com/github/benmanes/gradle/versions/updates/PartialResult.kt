package com.github.benmanes.gradle.versions.updates

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** One dependency's status, as observed by a single project. */
data class PartialStatus
  @JvmOverloads
  constructor(
    val group: String,
    val name: String,
    val declaredVersion: String,
    val userReason: String?,
    val latestVersion: String,
    val projectUrl: String?,
    val unresolved: UnresolvedInfo?,
    /** Whether only a lazy action contributed the dependency rather than the build declaring it. */
    val contributed: Boolean = false,
    /** The configurations a contributed dependency was declared against, empty when declared. */
    val configurations: List<String> = emptyList(),
    /** The observing project's build tree path, stamped by the accumulator rather than serialized. */
    @Transient val projectPath: String? = null,
    /**
     * The build tree paths of the platform projects the build imports this module through. Trails
     * the transient projectPath rather than preceding it, since inserting a parameter before it
     * would replace the shipped arities that end there.
     */
    val platformProjects: List<String> = emptyList(),
  ) {
    val coordinate: Coordinate
      get() = Coordinate(group, name, declaredVersion, userReason)

    val latestCoordinate: Coordinate
      get() = Coordinate(group, name, latestVersion, userReason)
  }

/** A resolution failure, as a value that survives the project boundary. */
data class UnresolvedInfo
  @JvmOverloads
  constructor(
    val selectorGroup: String,
    val selectorName: String,
    val selectorVersion: String,
    val failureText: String,
    /** The version of the declaration that failed, "none" when it declared none. */
    val declaredVersion: String = "none",
    val userReason: String? = null,
  )

/** A configuration skipped when its inspection failed, as a value that survives the project boundary. */
data class SkippedInfo(
  val name: String,
  val reason: String,
)

/** The statuses one project observed, as written by its producer task. */
data class PartialResult
  @JvmOverloads
  constructor(
    val formatVersion: Int,
    val projectPath: String,
    val statuses: List<PartialStatus>,
    val buildscriptStatuses: List<PartialStatus>,
    val skipped: List<SkippedInfo> = emptyList(),
  ) {
    fun toJson(): String = adapter.toJson(this)

    /**
     * Keeps the `copy` a release shipped callable, which the generated one no longer is now that
     * the skipped configurations moved it to five parameters.
     */
    fun copy(
      formatVersion: Int = this.formatVersion,
      projectPath: String = this.projectPath,
      statuses: List<PartialStatus> = this.statuses,
      buildscriptStatuses: List<PartialStatus> = this.buildscriptStatuses,
    ): PartialResult = copy(formatVersion, projectPath, statuses, buildscriptStatuses, skipped)

    companion object {
      /**
       * Bumped when the shape changes incompatibly; a field with a compatible default reads from an
       * older partial as that default.
       */
      const val FORMAT_VERSION: Int = 1

      private val adapter =
        Moshi.Builder()
          .addLast(KotlinJsonAdapterFactory())
          .build()
          .adapter(PartialResult::class.java)
          .serializeNulls()

      @JvmStatic
      fun fromJson(json: String): PartialResult {
        val result = requireNotNull(adapter.fromJson(json)) { "Empty partial result" }
        require(result.formatVersion == FORMAT_VERSION) {
          "Unsupported partial result format ${result.formatVersion}, expected $FORMAT_VERSION; re-run the build"
        }
        return result
      }
    }
  }

/**
 * Merges statuses observed across projects, keeping one entry per coordinate key unless a
 * concrete version displaces a `none` version.
 */
fun mergeStatuses(statuses: List<PartialStatus>): List<PartialStatus> {
  val merged = mutableListOf<PartialStatus>()
  for (status in statuses) {
    val index = merged.indexOfFirst { it.group == status.group && it.name == status.name }
    if (index < 0) {
      merged.add(status)
    } else if (status.declaredVersion != "none") {
      merged.add(status)
      if (merged[index].declaredVersion == "none") {
        merged.removeAt(index)
      }
    }
  }
  return merged
}
