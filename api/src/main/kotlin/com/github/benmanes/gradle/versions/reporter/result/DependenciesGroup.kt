package com.github.benmanes.gradle.versions.reporter.result

/**
 * A group of dependencies
 */
data class DependenciesGroup<T : Dependency> @JvmOverloads constructor(
  /**
   * The number of dependencies in this group
   */
  val count: Int,

  /**
   * The dependencies that belong to this group
   */
  val dependencies: Set<T> = sortedSetOf(),
)
