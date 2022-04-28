package com.github.benmanes.gradle.versions.reporter.result

data class DependencyOutdated @JvmOverloads constructor(
  override val group: String? = null,
  override val name: String? = null,
  override val version: String? = null,
  override val projectUrl: String? = null,
  override val userReason: String? = null,
  val available: VersionAvailable,
) : Dependency()
