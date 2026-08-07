package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

/**
 * A specification for the configuration filter, pinning the names the report shows against the
 * configurations the filter is offered.
 */
final class ConfigurationFilterSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  /**
   * A 'toolBucket' that cannot be resolved, filled by {@code defaultDependencies} and reached
   * through the resolvable 'toolClasspath' that extends it, as a declare/resolve plugin pairs them.
   */
  private static String bucketConfigurations(String taskBody = '') {
    """
      configurations.create('toolBucket') {
        canBeResolved = false
        canBeConsumed = false
      }
      configurations.create('toolClasspath') {
        canBeResolved = true
        canBeConsumed = false
        extendsFrom configurations.toolBucket
      }
      configurations.toolBucket.defaultDependencies { deps ->
        deps.add(project.dependencies.create('com.google.guava:guava:15.0'))
      }

      dependencyUpdates {
        $taskBody
      }
    """.stripIndent()
  }

  private void writeBuild(String projectBody) {
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        $projectBody

        dependencyUpdates {
          checkForGradleUpdate = false
        }
      """.stripIndent()
  }

  private def run(List<String> arguments) {
    return GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  def 'Names a configuration a plugin filled but cannot resolve'() {
    given:
    writeBuild(bucketConfigurations())

    when:
    def result = run(['dependencyUpdates'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(" - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}" +
      "     contributed by a plugin into the 'toolBucket' configuration")
  }

  def 'Keeps the entry when filterConfigurations rejects the configuration it names'() {
    given:
    writeBuild(bucketConfigurations("filterConfigurations { it.name != 'toolBucket' }"))

    when:
    def result = run(['dependencyUpdates'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(" - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}" +
      "     contributed by a plugin into the 'toolBucket' configuration")
  }

  def 'Leaves out the entry when filterConfigurations rejects the configuration that resolves it'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        dependencies {
          implementation 'com.google.inject:guice:3.1'
        }

        ${bucketConfigurations("filterConfigurations { it.name != 'toolClasspath' }")}
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains('com.google.guava:guava')
    // The rejection removes the entry rather than emptying the report.
    result.output.contains('com.google.inject:guice')
  }

  def 'Keeps the entry when another resolvable configuration still reaches it'() {
    given:
    writeBuild(
      """
        configurations.create('helper')
        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
          extendsFrom configurations.helper
        }
        configurations.helper.defaultDependencies { deps ->
          deps.add(project.dependencies.create('com.google.guava:guava:15.0'))
        }

        dependencyUpdates {
          filterConfigurations { it.name != 'helper' }
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(" - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}" +
      "     contributed by a plugin into the 'helper' configuration")
  }
}
