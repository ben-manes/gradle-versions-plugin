package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import groovy.transform.CompileStatic
import org.gradle.api.HasImplicitReceiver

@CompileStatic
@HasImplicitReceiver
interface ComponentFilter {

  boolean reject(ComponentSelectionWithCurrent candidate)

}
