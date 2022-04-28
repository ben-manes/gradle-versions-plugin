package com.github.benmanes.gradle.versions.reporter.result

import java.util.SortedSet

/**
 * A group of dependencies
 */
class DependenciesGroup<T : Dependency> @JvmOverloads constructor(
  /**
   * The number of dependencies in this group
   */
  val count: Int,

  /**
   * The dependencies that belong to this group
   */
  val dependencies: SortedSet<T> = sortedSetOf<T>()
)
