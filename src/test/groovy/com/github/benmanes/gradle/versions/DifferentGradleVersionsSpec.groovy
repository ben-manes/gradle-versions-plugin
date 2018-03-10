package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Unroll

final class DifferentGradleVersionsSpec extends BaseSpecification {
  @Unroll
  def "dependencyUpdates task completes without errors with Gradle #gradleVersion"() {
    given:
    def classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    def srdErrWriter = new StringWriter()

    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: "com.github.ben-manes.versions"

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          compile 'com.google.inject:guice:2.0'
        }
        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .forwardStdError(srdErrWriter)
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    srdErrWriter.toString().empty

    where:
    gradleVersion << [
      '1.5',
      '1.12',
      '2.0',
      '3.3',
      '3.4',
      '3.5',
      '4.0',
      '4.1',
      '4.2',
      '4.3',
      '4.4',
      '4.5',
      '4.6',
    ]
  }
}
