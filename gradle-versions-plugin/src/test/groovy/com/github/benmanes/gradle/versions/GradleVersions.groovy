package com.github.benmanes.gradle.versions

import org.gradle.util.GradleVersion
import spock.util.environment.Jvm

/**
 * The Gradle releases the functional specs run against, named once so that keeping up with a new
 * release is a single edit rather than a sweep of every spec that pins one.
 */
final class GradleVersions {
  /** The latest Gradle release, which every spec that exercises the Gradle 9 line runs against. */
  static final String CURRENT = '9.7.1'

  private static final GradleVersion GRADLE_9 = GradleVersion.version('9.0')
  private static final GradleVersion FIRST_ON_JAVA_25 = GradleVersion.version('9.1.0')

  /**
   * Returns whether the running JVM drives the given Gradle release, so that a spec pinning one
   * states the release it needs instead of carrying a guard written in terms of the JVM. A test
   * worker runs on whichever JDK its task names, and a TestKit daemon starts on the worker's JVM,
   * so the two ends of Gradle's compatibility matrix both bite here:
   * https://docs.gradle.org/current/userguide/compatibility.html
   */
  static boolean drivenBy(String gradleVersion) {
    // Java 8 names itself '1.8' where every later release names itself '21'.
    def specification = Jvm.current.javaSpecificationVersion
    def java = (specification.startsWith('1.') ? specification.substring(2) : specification).toInteger()
    def gradle = GradleVersion.version(gradleVersion).baseVersion
    // Gradle 9 does not start below Java 17, and Java 25 is listed from Gradle 9.1.0 onwards.
    return !(gradle >= GRADLE_9 && java < 17) && !(java >= 25 && gradle < FIRST_ON_JAVA_25)
  }
}
