package com.github.benmanes.gradle.versions.reporter.result

import groovy.transform.TupleConstructor

@TupleConstructor
class DependenciesGroup {
  int count
  List<Dependency> dependencies = [];
}
