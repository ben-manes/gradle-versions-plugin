package com.github.benmanes.gradle.versions.reporter.result

import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResults
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor

/**
 * The result of a dependency update analysis
 */
@CompileStatic
@TupleConstructor
class Result {
  /**
   * the overall number of dependencies in the project
   */
  int count

  /**
   * The up-to-date dependencies
   */
  DependenciesGroup<Dependency> current
  /**
   * The dependencies that can be updated
   */
  DependenciesGroup<DependencyOutdated> outdated
  /**
   * The dependencies whose versions are newer than the ones that are available from the repositories
   */
  DependenciesGroup<DependencyLatest> exceeded
  /**
   * The dependencies whose versions were not declared
   */
  DependenciesGroup<Dependency> undeclared
  /**
   * The unresolvable dependencies
   */
  DependenciesGroup<DependencyUnresolved> unresolved
  /**
   * Gradle release channels and respective update availability
   */
  GradleUpdateResults gradle
}
