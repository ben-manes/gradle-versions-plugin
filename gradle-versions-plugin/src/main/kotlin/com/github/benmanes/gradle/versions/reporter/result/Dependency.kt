package com.github.benmanes.gradle.versions.reporter.result

import com.squareup.moshi.JsonQualifier

/** Marks an optional report property that is omitted when null rather than serialized as null. */
@JsonQualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class AbsentWhenNull

/**
 * A project's dependency.
 */
open class Dependency
  @JvmOverloads
  constructor(
    open val group: String? = null,
    open val name: String? = null,
    open val version: String? = null,
    open val projectUrl: String? = null,
    open val userReason: String? = null,
    @AbsentWhenNull open val projects: List<String>? = null,
    /** True when only plugins contributed the dependency rather than the build declaring it. */
    @AbsentWhenNull open val contributed: Boolean? = null,
    /** The configurations a plugin contributed the dependency into, when it contributed it. */
    @AbsentWhenNull open val configurations: List<String>? = null,
  ) : Comparable<Dependency> {
    override fun compareTo(other: Dependency): Int {
      return compareValuesBy(
        this,
        other,
        { it.group },
        { it.name },
        { it.version },
        { it.projectUrl },
        { it.userReason },
      )
    }
  }
