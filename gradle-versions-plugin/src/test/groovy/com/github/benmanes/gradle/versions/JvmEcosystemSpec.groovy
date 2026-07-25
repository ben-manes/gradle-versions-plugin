package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

final class JvmEcosystemSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/746')
  def 'Show updates for a jvm dependency in a project without a jvm plugin'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        def deps = configurations.dependencyScope('deps')
        configurations.resolvable('depsClasspath') {
          extendsFrom deps.get()
        }

        dependencies {
          add('deps', 'com.example:jvm-library:1.0')
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('com.example:jvm-library [1.0 -> 2.0]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }
}
