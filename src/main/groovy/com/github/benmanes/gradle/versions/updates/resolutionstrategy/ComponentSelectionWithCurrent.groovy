package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor
import org.gradle.api.artifacts.ComponentSelection

@CompileStatic
@TupleConstructor(includeFields = true)
class ComponentSelectionWithCurrent {

  final String currentVersion

  @Delegate
  private final ComponentSelection delegate

  @Override
  String toString() {
    return """\
ComponentSelectionWithCurrent{
    group="${candidate.group}",
    module="${candidate.module}",
    version="${candidate.version}",
    currentVersion="$currentVersion",
}"""
  }
}

