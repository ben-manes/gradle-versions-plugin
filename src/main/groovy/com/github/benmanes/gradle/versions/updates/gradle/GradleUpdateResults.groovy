package com.github.benmanes.gradle.versions.updates.gradle

import groovy.transform.TupleConstructor

/**
 * Wrapper holder class for gradle update results of all release channel (including the running version).
 * Used for reporting & serialization to JSON/XML
 */
@TupleConstructor
class GradleUpdateResults {

  GradleUpdateResult running
  GradleUpdateResult current
  GradleUpdateResult releaseCandidate
  GradleUpdateResult nightly

}
