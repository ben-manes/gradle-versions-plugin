package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for reporting the declaration whose resolution failed, rather than another
 * declaration of the same module.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1043')
final class UnresolvedVersionSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  private void writeRoot(List<String> projects) {
    testProjectDir.newFile('settings.gradle') << "include ${projects.collect { "'$it'" }.join(', ')}"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencyUpdates {
          checkForGradleUpdate = false
        }
      """.stripIndent()
  }

  /** Writes a project holding the given body, seeing a repository only when one is asked for. */
  private void writeProject(String name, String body, boolean seeing = false) {
    def repositories = seeing ? "repositories { maven { url '${mavenRepoUrl}' } }" : ''
    testProjectDir.newFolder(name)
    testProjectDir.newFile("$name/build.gradle") <<
      """
        apply plugin: 'java'

        $repositories

        $body
      """.stripIndent()
  }

  private static String declaring(String coordinate, String reason = null) {
    def because = reason ? "{ because '$reason' }" : ''
    return "dependencies { implementation('$coordinate') $because }"
  }

  private def run(List<String> arguments) {
    return GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  private def unresolved() {
    return new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
      .unresolved.dependencies
  }

  def 'Reports the version that failed to resolve rather than the lowest declared'() {
    given:
    writeRoot(['blind', 'seeing'])
    writeProject('blind', declaring('com.google.inject:guice:3.0', 'blind pinned 3.0'))
    writeProject('seeing', declaring('com.google.inject:guice:2.0', 'seeing declared 2.0'), true)

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=plain,json', '--no-parallel'])
    def unresolved = unresolved()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    unresolved.size() == 1
    unresolved[0].version == '3.0'
    unresolved[0].userReason == 'blind pinned 3.0'
    def unresolvedSection = result.output.substring(result.output.indexOf('Failed to determine'))
    unresolvedSection.contains(' - com.google.inject:guice:3.0')
    unresolvedSection.contains('blind pinned 3.0')
    !unresolvedSection.contains('seeing declared 2.0')
  }

  def 'Reports every declared version that failed to resolve'() {
    given:
    writeRoot(['blind', 'alsoBlind'])
    writeProject('blind', declaring('com.google.inject:guice:2.0'))
    writeProject('alsoBlind', declaring('com.google.inject:guice:3.0'))

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=json', '--no-parallel'])
    def unresolved = unresolved()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    unresolved.size() == 2
    unresolved*.version.sort() == ['2.0', '3.0']
  }

  def 'Marks the contributed declaration that failed to resolve'() {
    given:
    writeRoot(['blind', 'seeing'])
    writeProject(
      'blind',
      """
        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }
        configurations.tool.defaultDependencies { deps ->
          deps.add(project.dependencies.create('com.google.guava:guava:16.0-rc1'))
        }
      """.stripIndent())
    writeProject('seeing', declaring('com.google.guava:guava:15.0'), true)

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=json', '--no-parallel'])
    def unresolved = unresolved()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    unresolved.size() == 1
    unresolved[0].version == '16.0-rc1'
    unresolved[0].contributed == true
    unresolved[0].configurations == ['tool']
  }
}
