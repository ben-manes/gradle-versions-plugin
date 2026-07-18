package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Unroll

@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
final class AggregationSpec extends Specification {
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

        allprojects {
          apply plugin: 'java'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.guava:guava:15.0'
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

  def 'Aggregates the updates of every project'() {
    when:
    def result = run(['dependencyUpdates', '-Dcom.github.benmanes.versions.aggregate=true'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
  }

  def 'Aggregates in parallel with the configuration cache'() {
    when:
    def first = run(['dependencyUpdates', '-Dcom.github.benmanes.versions.aggregate=true',
                     '--parallel', '--configuration-cache'])
    def second = run(['dependencyUpdates', '-Dcom.github.benmanes.versions.aggregate=true',
                      '--parallel', '--configuration-cache'])

    then:
    first.task(':dependencyUpdates').outcome == SUCCESS
    second.task(':dependencyUpdates').outcome == SUCCESS
    second.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    second.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
  }

  // Gradle 9 requires JVM 17.
  @Requires({ jvm.java17Compatible })
  @Unroll
  def 'Aggregates in parallel on Gradle #gradleVersion'() {
    when:
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '-Dcom.github.benmanes.versions.aggregate=true',
        '--parallel', '--configuration-cache')
      .withPluginClasspath()
      .build()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')

    where:
    gradleVersion << ['9.0.0', '9.6.1']
  }

  def 'Reports the same results as the legacy topology'() {
    given:
    def arguments = ['dependencyUpdates', '-DoutputFormatter=json', '--no-parallel']

    when:
    run(arguments)
    def legacy = new File(testProjectDir.root, 'build/dependencyUpdates/report.json').text
    run(arguments + ['-Dcom.github.benmanes.versions.aggregate=true'])
    def aggregated = new File(testProjectDir.root, 'build/dependencyUpdates/report.json').text

    then:
    aggregated == legacy
  }
}
