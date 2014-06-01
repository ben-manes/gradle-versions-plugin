package com.github.benmanes.gradle.versions.reporter.result

import groovy.transform.TupleConstructor

@TupleConstructor
class Result {
  int count
  DependenciesGroup current
  DependenciesGroup outdated
  DependenciesGroup exceeded
  DependenciesGroup unresolved
}
