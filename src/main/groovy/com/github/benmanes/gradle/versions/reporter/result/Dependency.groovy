package com.github.benmanes.gradle.versions.reporter.result

import groovy.transform.TupleConstructor

@TupleConstructor(includeFields = true)
class Dependency {
  String name
  String group
  String version
}
