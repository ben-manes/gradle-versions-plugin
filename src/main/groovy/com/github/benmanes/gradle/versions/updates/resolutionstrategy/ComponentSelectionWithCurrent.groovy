package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import groovy.transform.TupleConstructor
import org.gradle.api.artifacts.ComponentSelection

@TupleConstructor(includeFields=true)
class ComponentSelectionWithCurrent {

  final String currentVersion

  @Delegate
  private final ComponentSelection delegate
}
