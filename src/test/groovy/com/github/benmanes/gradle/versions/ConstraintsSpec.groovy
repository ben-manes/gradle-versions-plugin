package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

final class ConstraintsSpec extends Specification {

  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  def "Show updates for an api dependency constraint"() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'com.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def "Does not override explicit dependency with constraint"() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'com.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:3.0'
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('com.google.inject:guice [3.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def "Does not show updates for an api dependency constraint when disabled"() {
    given:
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'com.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('No dependencies found.')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }
}
