package com.github.benmanes.gradle.versions.reporter.result

import groovy.transform.CompileStatic
import groovy.transform.Sortable
import groovy.transform.TupleConstructor
import javax.annotation.Nullable

/**
 * A project's dependency
 */
@CompileStatic
@Sortable
@TupleConstructor(includeFields = true)
class Dependency {
  String group
  String name
  @Nullable String version
  @Nullable String projectUrl
  @Nullable String userReason
}
