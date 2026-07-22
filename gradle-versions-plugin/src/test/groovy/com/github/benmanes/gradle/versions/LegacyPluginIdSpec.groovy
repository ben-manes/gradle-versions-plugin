package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

final class LegacyPluginIdSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String classpathString

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.getResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        "Did not find plugin classpath resource, run `testClasses` build task.")
    }

    classpathString = pluginClasspathResource.readLines()
      .collect { it.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
  }

  def 'deprecated com.github id still works and warns'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'com.github.ben-manes.versions'
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .build()

    then:
    result.output.contains(
      'The com.github.ben-manes.versions plugin id is deprecated; apply io.github.ben-manes.versions instead.')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'applying both the deprecated and the current id is harmless'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'com.github.ben-manes.versions'
        apply plugin: 'io.github.ben-manes.versions'
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .build()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
  }
}
