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
    /**
     * The configurations the dependency was declared directly against, or the ones a plugin
     * contributed it into when [contributed] is true.
     */
    @AbsentWhenNull open val configurations: List<String>? = null,
    /** The platform projects the build imports this dependency through, by build tree path. */
    @AbsentWhenNull open val platformProjects: List<String>? = null,
    /**
     * The platforms that constrain this dependency's version, a project one by its build tree path
     * and an external one by its module coordinate.
     */
    @AbsentWhenNull open val constrainedBy: List<String>? = null,
  ) : Comparable<Dependency> {
    /**
     * The projects are compared last, so that two rows of one declared version with different
     * latest versions are ordered apart. The report groups are sorted sets, and rows that compare
     * as equal are dropped from them rather than printed. The subclasses with a latest version
     * compare that too, so that the ordering does not rest on the projects alone.
     *
     * The separator is one a build tree path cannot contain, so that ["a", "b"] and ["a,b"] are not
     * compared as one list.
     */
    override fun compareTo(other: Dependency): Int {
      return compareValuesBy(
        this,
        other,
        { it.group },
        { it.name },
        { it.version },
        { it.projectUrl },
        { it.userReason },
        { it.projects?.joinToString("\u0000") },
      )
    }
  }
