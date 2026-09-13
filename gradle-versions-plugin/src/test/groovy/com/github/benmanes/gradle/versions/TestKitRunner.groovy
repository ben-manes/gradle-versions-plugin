package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner

/**
 * Creates the runners of the functional specs, so that a task forking its test worker onto an older
 * JDK also drives a Gradle release that JDK starts. A TestKit daemon runs on the worker's JVM, and
 * the release running this build is too new for the oldest JVMs the plugin supports.
 */
final class TestKitRunner {
  private static final String PINNED = System.getProperty('testGradleVersion')

  /**
   * Returns a runner on the release the task pinned, or on the release running this build where the
   * task pinned none. A spec that names its own release overrides either.
   */
  static GradleRunner create() {
    def runner = GradleRunner.create()
    return PINNED ? runner.withGradleVersion(PINNED) : runner
  }
}
