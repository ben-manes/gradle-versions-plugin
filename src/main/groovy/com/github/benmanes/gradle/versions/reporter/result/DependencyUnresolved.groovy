package com.github.benmanes.gradle.versions.reporter.result

import groovy.transform.TupleConstructor

@TupleConstructor(callSuper = true, includeSuperProperties = true, includeSuperFields = true)
class DependencyUnresolved extends Dependency {
  String reason
}