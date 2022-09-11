package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import org.gradle.api.HasImplicitReceiver

@HasImplicitReceiver
fun interface ComponentFilter {
  fun reject(candidate: ComponentSelectionWithCurrent): Boolean
}
