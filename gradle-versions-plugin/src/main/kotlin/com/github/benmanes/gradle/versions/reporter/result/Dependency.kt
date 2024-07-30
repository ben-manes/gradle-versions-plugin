package com.github.benmanes.gradle.versions.reporter.result

import com.squareup.moshi.JsonClass

/**
 * A project's dependency.
 */
@JsonClass(generateAdapter = true)
open class Dependency
  @JvmOverloads
  constructor(
    open val group: String? = null,
    open val name: String? = null,
    open val version: String? = null,
    open val projectUrl: String? = null,
    open val userReason: String? = null,
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
