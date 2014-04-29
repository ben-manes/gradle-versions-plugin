package com.github.benmanes.gradle.versions.reporter.result

import groovy.transform.TupleConstructor

@TupleConstructor
class VersionAvailable {
  String release
  String milestone
  String integration
}