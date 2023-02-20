package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import org.gradle.api.artifacts.ComponentSelection

class ComponentSelectionWithCurrent(
  private val delegate: ComponentSelection,
  val currentVersion: String,
) : ComponentSelection by delegate {

  override fun toString(): String {
    return """\
ComponentSelectionWithCurrent{
    group="${candidate.group.orEmpty()}",
    module="${candidate.module}",
    version="${candidate.version}",
    currentVersion="$currentVersion",
}"""
  }
}
