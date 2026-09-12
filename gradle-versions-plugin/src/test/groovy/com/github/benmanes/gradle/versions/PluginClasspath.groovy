package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner

/**
 * The plugin's classpath, read from the metadata that the {@code java-gradle-plugin} generates, for
 * the specs that write it into a build script instead of injecting it with
 * {@link GradleRunner#withPluginClasspath()}.
 */
final class PluginClasspath {
  /** Returns the classpath as the quoted, comma-separated argument of a {@code files(...)} call. */
  static String asFilesArgument() {
    return GradleRunner.create().withPluginClasspath().pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(', ')
  }
}
