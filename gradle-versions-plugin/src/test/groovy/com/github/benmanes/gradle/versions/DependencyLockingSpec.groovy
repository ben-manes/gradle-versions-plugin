package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

final class DependencyLockingSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()

  @Unroll
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/781')
  def 'Show updates for a build using strict dependency locking activated by #activation'() {
    given:
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    def buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencyLocking {
          lockMode = LockMode.STRICT
        }

        ${script}

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

    where:
    activation               | script
    'a top-level hook'       | 'configurations.all { resolutionStrategy.activateDependencyLocking() }'
    'an afterEvaluate hook'  | 'afterEvaluate { configurations.all { resolutionStrategy.activateDependencyLocking() } }'
    'lockAllConfigurations'  | 'dependencyLocking { lockAllConfigurations() }'
  }
}
