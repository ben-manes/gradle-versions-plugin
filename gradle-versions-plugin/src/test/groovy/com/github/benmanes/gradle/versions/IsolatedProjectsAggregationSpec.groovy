package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Requires
import spock.lang.Specification

// Gradle 9 requires JVM 17.
@Requires({ jvm.java17Compatible })
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
final class IsolatedProjectsAggregationSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
    testProjectDir.newFile('settings.gradle') << "include 'app', 'lib'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'com.github.ben-manes.versions'
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        plugins {
          id 'java'
          id 'com.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        plugins {
          id 'java'
          id 'com.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()
  }

  private def run() {
    return GradleRunner.create()
      .withGradleVersion('9.7.0-rc-1')
      .withProjectDir(testProjectDir.root)
      .withArguments(':dependencyUpdates', '-Dcom.github.benmanes.versions.aggregate=true',
        '-Dorg.gradle.isolated-projects=true', '--configuration-cache')
      .withPluginClasspath()
      .build()
  }

  def 'Aggregates when applied per project with isolated projects'() {
    when:
    def result = run()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    !result.output.contains('The dependency updates report is missing')
  }

  def 'Honors the root task settings in every projects producer'() {
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        dependencyUpdates.resolutionStrategy {
          componentSelection {
            all {
              if (candidate.version == '3.1') {
                reject('unstable')
              }
            }
          }
        }
      """.stripIndent()

    when:
    def result = run()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
  }

  def 'Omits and warns about a project that does not apply the plugin itself'() {
    given:
    new File(testProjectDir.root, 'lib/build.gradle').text =
      """
        plugins {
          id 'java'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()

    when:
    def result = run()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    // Isolated projects lets the root discover the project paths but not register a task in them,
    // so a project without the plugin publishes nothing to aggregate and is called out instead.
    // Registering the producers from a settings plugin is the only fix, which is a change to how
    // the plugin is applied.
    !result.output.contains('com.google.guava:guava')
    result.output.contains('The dependency updates report is missing :lib')
  }
}
