package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import org.gradle.api.HasImplicitReceiver

@HasImplicitReceiver
interface ComponentFilter {

  boolean reject(ComponentSelectionWithCurrent candidate)

}
