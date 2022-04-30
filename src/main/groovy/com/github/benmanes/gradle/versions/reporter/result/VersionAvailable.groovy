package com.github.benmanes.gradle.versions.reporter.result

import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor
import javax.annotation.Nullable

@CompileStatic
@TupleConstructor
class VersionAvailable {
  @Nullable String release
  @Nullable String milestone
  @Nullable String integration
}
