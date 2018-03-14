package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

final class JavaLibrarySpec extends BaseSpecification {
  def "Show updates for an api dependency in a java-library project"() {
    given:
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()

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
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }
}
