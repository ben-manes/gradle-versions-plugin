package com.github.benmanes.gradle.versions.reporter.result

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DependencyLatest(
  override val group: String? = null,
  override val name: String? = null,
  override val version: String? = null,
  override val projectUrl: String? = null,
  override val userReason: String? = null,
  val latest: String,
) : Dependency()
