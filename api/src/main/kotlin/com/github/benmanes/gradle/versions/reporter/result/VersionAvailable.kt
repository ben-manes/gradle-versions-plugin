package com.github.benmanes.gradle.versions.reporter.result

class VersionAvailable @JvmOverloads constructor(
  val release: String? = null,
  val milestone: String? = null,
  val integration: String? = null,
)
