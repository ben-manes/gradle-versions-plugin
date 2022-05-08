package com.github.benmanes.gradle.versions.reporter.result

import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResults

/**
 * The result of a dependency update analysis
 */
class Result(
  /**
   * the overall number of dependencies in the project
   */
  val count: Int,

  /**
   * The up-to-date dependencies
   */
  val current: DependenciesGroup<Dependency>,

  /**
   * The dependencies that can be updated
   */
  val outdated: DependenciesGroup<DependencyOutdated>,

  /**
   * The dependencies whose versions are newer than the ones that are available from the repositories
   */
  val exceeded: DependenciesGroup<DependencyLatest>,

  /**
   * The dependencies whose versions were not declared
   */
  val undeclared: DependenciesGroup<Dependency>,

  /**
   * The unresolvable dependencies
   */
  val unresolved: DependenciesGroup<DependencyUnresolved>,

  /**
   * Gradle release channels and respective update availability
   */
  val gradle: GradleUpdateResults,
)
