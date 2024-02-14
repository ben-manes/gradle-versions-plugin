package com.github.benmanes.gradle.versions.reporter.result

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
