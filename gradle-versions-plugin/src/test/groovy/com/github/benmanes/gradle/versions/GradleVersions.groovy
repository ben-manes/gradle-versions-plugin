package com.github.benmanes.gradle.versions

/**
 * The Gradle releases the functional specs run against, named once so that keeping up with a new
 * release is a single edit rather than a sweep of every spec that pins one.
 */
final class GradleVersions {
  /** The latest Gradle release, which every spec that exercises the Gradle 9 line runs against. */
  static final String CURRENT = '9.7.1'
}
