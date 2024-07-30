package com.github.benmanes.gradle.versions.reporter.result

import com.squareup.moshi.JsonClass

/**
 * A group of dependencies.
 *
 * @property count The number of dependencies in this group.
 * @property dependencies The dependencies that belong to this group.
 */
@JsonClass(generateAdapter = true)
data class DependenciesGroup<T : Dependency>(
  val count: Int,
  val dependencies: MutableSet<T> = mutableSetOf(),
)
