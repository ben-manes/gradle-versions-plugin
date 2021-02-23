package com.github.benmanes.gradle.versions.reporter.result

import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor

@CompileStatic
@TupleConstructor
class VersionAvailable {
  String release
  String milestone
  String integration
}
