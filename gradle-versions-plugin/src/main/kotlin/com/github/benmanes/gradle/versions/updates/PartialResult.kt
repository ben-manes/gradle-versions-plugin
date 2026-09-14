package com.github.benmanes.gradle.versions.updates

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** A declared `VersionConstraint`, serialized as its four getters verbatim; empty when unset. */
data class ConstraintInfo(
  val required: String,
  val strict: String,
  val preferred: String,
  val rejected: List<String>,
)

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
    /**
     * The platforms that constrain this module's version, a project one by its build tree path and
     * an external one by its module coordinate. Trails for the same reason as [platformProjects].
     */
    val constrainedBy: List<String> = emptyList(),
    /**
     * Whether one declared version of this module has different latest versions across the
     * aggregated projects, which is settled by the aggregating task rather than by the producer.
     * Trails for the same reason as [platformProjects], and transient because it is a fact about
     * the whole report rather than about the project whose producer wrote this status.
     */
    @Transient val splitByLatest: Boolean = false,
    /** The constraint declared for this module, null when no declaration covers it. */
    val constraint: ConstraintInfo? = null,
    /** The constraints declared for this module by the platforms its consumer depends on. */
    val platformConstraints: List<ConstraintInfo> = emptyList(),
    /**
     * Whether the build declared this module on a script classpath, which is what lets a dynamic
     * required version be read as a bound. Trails for the same reason as [platformProjects].
     * https://github.com/ben-manes/gradle-versions-plugin/issues/755
     */
    val onScriptClasspath: Boolean = false,
    /**
     * The newest candidate left out by the producer's pre-release check, null when none was left
     * out. Trails for the same reason as [platformProjects].
     */
    val preReleaseVersion: String? = null,
    /**
     * Whether Gradle sets this version for its embedded Kotlin in the observing project, left out of
     * the report unless `checkEmbeddedKotlin` is set. Trails for the same reason as
     * [platformProjects].
     */
    val embeddedKotlin: Boolean = false,
    /**
     * The newest version sharing the major and minor parts of the declared one, null where none is
     * newer than it or the declared version has no numeric minor part. Trails for the same reason
     * as [platformProjects].
     * https://github.com/ben-manes/gradle-versions-plugin/issues/69
     */
    val patchVersion: String? = null,
    /** The newest version sharing the major part of the declared one, null on the same terms. */
    val minorVersion: String? = null,
  ) {
    val coordinate: Coordinate
      get() = Coordinate(group, name, declaredVersion, userReason, divergentLatest)

    val latestCoordinate: Coordinate
      get() = Coordinate(group, name, latestVersion, userReason, divergentLatest)

    /** The version that separates a split row from the other rows of its declared version. */
    private val divergentLatest: String?
      get() = latestVersion.takeIf { splitByLatest }

    /**
     * Keeps the `copy` a release shipped callable, which the generated one no longer is now that
     * the imported platform projects moved it past ten parameters.
     */
    fun copy(
      group: String = this.group,
      name: String = this.name,
      declaredVersion: String = this.declaredVersion,
      userReason: String? = this.userReason,
      latestVersion: String = this.latestVersion,
      projectUrl: String? = this.projectUrl,
      unresolved: UnresolvedInfo? = this.unresolved,
      contributed: Boolean = this.contributed,
      configurations: List<String> = this.configurations,
      projectPath: String? = this.projectPath,
    ): PartialStatus =
      copy(
        group, name, declaredVersion, userReason, latestVersion, projectUrl, unresolved, contributed,
        configurations, projectPath, platformProjects, constrainedBy, splitByLatest,
      )

    /**
     * Keeps the `copy` a release shipped callable. The generated one no longer is, now that the
     * constraining platforms moved it past eleven parameters.
     */
    fun copy(
      group: String = this.group,
      name: String = this.name,
      declaredVersion: String = this.declaredVersion,
      userReason: String? = this.userReason,
      latestVersion: String = this.latestVersion,
      projectUrl: String? = this.projectUrl,
      unresolved: UnresolvedInfo? = this.unresolved,
      contributed: Boolean = this.contributed,
      configurations: List<String> = this.configurations,
      projectPath: String? = this.projectPath,
      platformProjects: List<String> = this.platformProjects,
    ): PartialStatus =
      copy(
        group, name, declaredVersion, userReason, latestVersion, projectUrl, unresolved, contributed,
        configurations, projectPath, platformProjects, constrainedBy, splitByLatest,
      )

    /**
     * Keeps the `copy` a release shipped callable. The generated one no longer is, now that the
     * split marker moved it past twelve parameters.
     */
    fun copy(
      group: String = this.group,
      name: String = this.name,
      declaredVersion: String = this.declaredVersion,
      userReason: String? = this.userReason,
      latestVersion: String = this.latestVersion,
      projectUrl: String? = this.projectUrl,
      unresolved: UnresolvedInfo? = this.unresolved,
      contributed: Boolean = this.contributed,
      configurations: List<String> = this.configurations,
      projectPath: String? = this.projectPath,
      platformProjects: List<String> = this.platformProjects,
      constrainedBy: List<String> = this.constrainedBy,
    ): PartialStatus =
      copy(
        group, name, declaredVersion, userReason, latestVersion, projectUrl, unresolved, contributed,
        configurations, projectPath, platformProjects, constrainedBy, splitByLatest,
      )

    /**
     * Keeps the `copy` a release shipped callable. The generated one no longer is, now that the
     * declared and platform constraints moved it past thirteen parameters.
     */
    fun copy(
      group: String = this.group,
      name: String = this.name,
      declaredVersion: String = this.declaredVersion,
      userReason: String? = this.userReason,
      latestVersion: String = this.latestVersion,
      projectUrl: String? = this.projectUrl,
      unresolved: UnresolvedInfo? = this.unresolved,
      contributed: Boolean = this.contributed,
      configurations: List<String> = this.configurations,
      projectPath: String? = this.projectPath,
      platformProjects: List<String> = this.platformProjects,
      constrainedBy: List<String> = this.constrainedBy,
      splitByLatest: Boolean = this.splitByLatest,
    ): PartialStatus =
      copy(
        group, name, declaredVersion, userReason, latestVersion, projectUrl, unresolved, contributed,
        configurations, projectPath, platformProjects, constrainedBy, splitByLatest, constraint,
        platformConstraints,
      )
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
    /** Every candidate version a dynamic query reached, as `group:name:version`. */
    val candidates: List<String> = emptyList(),
    /** Whether the producer marked the statuses Gradle sets for its embedded Kotlin, false when older. */
    val marksEmbeddedKotlin: Boolean = false,
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
    ): PartialResult = copy(formatVersion, projectPath, statuses, buildscriptStatuses, skipped, candidates)

    companion object {
      /**
       * Bumped when the shape changes incompatibly; a field with a compatible default reads from an
       * older partial as that default. 2 records every candidate a dynamic query reaches rather
       * than only the accepted one, plus the declared and platform-supplied constraints. 3 holds the
       * newest release in `latestVersion` whatever `rejectPreReleases` is set to, with the newest
       * pre-release beside it in `preReleaseVersion`, where 2 held whichever of the two that setting
       * selected. The new field alone would read from a 2 as its default, but an older report
       * reading a 3 would take `latestVersion` under the earlier meaning and drop the pre-release
       * with nothing said, so the bump is what makes that combination fail instead.
       */
      const val FORMAT_VERSION: Int = 3

      private val adapter =
        Moshi.Builder()
          .addLast(KotlinJsonAdapterFactory())
          .build()
          .adapter(PartialResult::class.java)
          .serializeNulls()

      @JvmStatic
      fun fromJson(json: String): PartialResult {
        val result = requireNotNull(adapter.fromJson(json)) { "Empty partial result" }
        require(result.formatVersion in 1..FORMAT_VERSION) {
          "Unsupported partial result format ${result.formatVersion} for '${result.projectPath}', " +
            "expected 1..$FORMAT_VERSION. It was written by a newer version of the plugin than the " +
            "report reading it; apply one version of the plugin across every project and included build."
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
