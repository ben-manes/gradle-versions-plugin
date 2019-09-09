package com.github.benmanes.gradle.versions.updates.resolutionstrategy

interface ComponentFilter {

  boolean reject(ComponentSelectionWithCurrent candidate)

}
