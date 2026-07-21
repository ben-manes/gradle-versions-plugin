package com.github.benmanes.gradle.versions.updates

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** One dependency's status, as observed by a single project. */
data class PartialStatus(
  val group: String,
  val name: String,
  val declaredVersion: String,
  val userReason: String?,
  val latestVersion: String,
  val projectUrl: String?,
  val unresolved: UnresolvedInfo?,
) {
  val coordinate: Coordinate
    get() = Coordinate(group, name, declaredVersion, userReason)

  val latestCoordinate: Coordinate
    get() = Coordinate(group, name, latestVersion, userReason)
}

/** A resolution failure, as a value that survives the project boundary. */
data class UnresolvedInfo(
  val selectorGroup: String,
  val selectorName: String,
  val selectorVersion: String,
  val failureText: String,
)

/** The statuses one project observed, as written by its producer task. */
data class PartialResult(
  val formatVersion: Int,
  val projectPath: String,
  val statuses: List<PartialStatus>,
  val buildscriptStatuses: List<PartialStatus>,
) {
  fun toJson(): String = adapter.toJson(this)

  companion object {
    /** Bumped when the shape changes; stale partials are rejected rather than misread. */
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
