package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for how the reporters respect the build's log level.
 */
final class ReporterLogLevelSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String classpathString
  private String mavenRepoUrl

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
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/278')
  def 'The summary is printed at the lifecycle level but not under --quiet'() {
    given:
    buildFileWith('')

    when:
    def result = run(arguments)

    then:
    result.output.contains('Project Dependency Updates') == printed
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    arguments                        || printed
    ['dependencyUpdates']            || true
    ['dependencyUpdates', '--quiet'] || false
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/388')
  def 'An empty outputFormatter does not announce the skipped report file'() {
    given:
    buildFileWith("outputFormatter = ''")

    when:
    def result = run(['dependencyUpdates'])

    then:
    !result.output.contains('Skip generating report to file')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/726')
  def 'The unresolved hint suggests --info only when it is not already enabled'() {
    given:
    buildFileWith('')

    when:
    def result = run(arguments)

    then:
    result.output.contains('Failed to determine the latest version for the following dependencies')
    result.output.contains('use --info for details') == hinted
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    arguments                       || hinted
    ['dependencyUpdates']           || true
    ['dependencyUpdates', '--info'] || false
  }

  private void buildFileWith(String extraConfiguration) {
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.github.ben-manes:unresolvable:1.0'
        }

        dependencyUpdates {
          checkForGradleUpdate = false // future proof tests from breaking
          $extraConfiguration
        }
      """.stripIndent()
  }

  private def run(List<String> arguments) {
    return GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .build()
  }
}
