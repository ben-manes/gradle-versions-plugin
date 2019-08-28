package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import org.gradle.api.artifacts.ComponentSelection

class ComponentSelectionWithCurrent {

  @Delegate
  private ComponentSelection delegate

  private String current

  ComponentSelectionWithCurrent(ComponentSelection delegate, String current) {
    this.delegate = delegate
    this.current = current
  }

  String getCurrent() {
    return current
  }
}
