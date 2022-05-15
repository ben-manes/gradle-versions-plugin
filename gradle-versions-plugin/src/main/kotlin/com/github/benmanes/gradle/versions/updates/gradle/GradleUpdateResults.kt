package com.github.benmanes.gradle.versions.updates.gradle

/**
 * Wrapper holder class for gradle update results of all release channel (including the running
 * version). Used for reporting & serialization to JSON/XML
 */
class GradleUpdateResults(
  val enabled: Boolean = false,
  val running: GradleUpdateResult,
  val current: GradleUpdateResult,
  val releaseCandidate: GradleUpdateResult,
  val nightly: GradleUpdateResult,
)
