package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

final class UnresolvedReasonSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()

  def "State why a dependency could not be resolved"() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()

    when:
    def result = TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('com.google.inject:guice:2.0')
    result.output.contains('because no repositories are defined')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def "Resolve against the repositories the settings script supplies"() {
    given:
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    testProjectDir.newFile('settings.gradle') <<
      """
        dependencyResolutionManagement {
          repositories {
            maven { url '${mavenRepoUrl}' }
          }
        }
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()

    when:
    def result = TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    // The project declares no repository of its own, so a report that predicted resolvability from
    // `project.repositories` would drop this build's whole report rather than the row it earns.
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    !result.output.contains('Failed to determine the latest version')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }
}
