package com.github.benmanes.gradle.versions.reporter.result

import groovy.transform.CompileStatic
import groovy.transform.Sortable
import groovy.transform.TupleConstructor

/**
 * A project's dependency
 */
@CompileStatic
@Sortable
@TupleConstructor(includeFields = true)
class Dependency {
  String group
  String name
  String version
  String projectUrl
  String userReason
}
