package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

class ComponentSelectionWithCurrent {

  private ComponentSelection delegate

  private String current

  ComponentSelectionWithCurrent(ComponentSelection delegate, String current) {
    this.delegate = delegate
    this.current = current
  }

  ModuleComponentIdentifier getCandidate() {
    return delegate.getCandidate()
  }

  String getCurrent() {
    return current
  }

  void reject(String reason) {
    delegate.reject(reason)
  }
}
