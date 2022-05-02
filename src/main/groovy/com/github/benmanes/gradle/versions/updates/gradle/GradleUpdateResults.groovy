package com.github.benmanes.gradle.versions.updates.gradle

import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor
import javax.annotation.Nullable

/**
 * Wrapper holder class for gradle update results of all release channel (including the running version).
 * Used for reporting & serialization to JSON/XML
 */
@CompileStatic
@TupleConstructor
class GradleUpdateResults {
  boolean enabled = false
  @Nullable GradleUpdateResult running
  @Nullable GradleUpdateResult current
  @Nullable GradleUpdateResult releaseCandidate
  @Nullable GradleUpdateResult nightly
}
