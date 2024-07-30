package com.github.benmanes.gradle.versions.reporter.result

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
class VersionAvailable
  @JvmOverloads
  constructor(
    val release: String? = null,
    val milestone: String? = null,
    val integration: String? = null,
  ) {
    operator fun get(revision: String): String? {
      return when (revision) {
        "release" -> release
        "milestone" -> milestone
        "integration" -> integration
        else -> {
          ""
        }
      }
    }
  }
