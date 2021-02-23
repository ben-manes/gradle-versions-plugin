package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

final class JavaLibrarySpec extends Specification {

  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  def "Show updates for an api dependency in a java-library project"() {
    given:
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
          api 'com.google.inject:guice:2.0'
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
}
