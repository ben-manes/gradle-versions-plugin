package com.github.benmanes.gradle.versions.reporter.result

import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor

/**
 * A group of dependencies
 */
@CompileStatic
@TupleConstructor
class DependenciesGroup<T extends Dependency> {

  /**
   * The number of dependencies in this group
   */
  int count

  /**
   * The dependencies that belong to this group
   */
  SortedSet<T> dependencies = [] as SortedSet<T>
}
