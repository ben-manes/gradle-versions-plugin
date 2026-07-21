package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
final class KotlinMultiplatformAggregationSpec extends Specification {
  private static final String KOTLIN_VERSION = '2.0.21'
  private static final List<String> ARGUMENTS = ['dependencyUpdates', '-DoutputFormatter=json']

  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()

  def 'setup'() {
    testProjectDir.newFile('settings.gradle') << "include 'kmp', 'jvm'"
    testProjectDir.newFile('gradle.properties') << 'kotlin.mpp.stability.nowarn=true\n'
    // The Kotlin plugin is declared once by the root, as resolving it from a subproject loads it in
    // a second classloader that the configuration cache cannot serialize.
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'com.github.ben-manes.versions'
          id 'org.jetbrains.kotlin.multiplatform' version '${KOTLIN_VERSION}' apply false
        }

        allprojects {
          repositories {
            mavenCentral()
          }
        }

        dependencyUpdates {
          checkForGradleUpdate = false
        }
      """.stripIndent()
    testProjectDir.newFolder('kmp')
    testProjectDir.newFile('kmp/build.gradle') <<
      """
        apply plugin: 'org.jetbrains.kotlin.multiplatform'

        kotlin {
          jvm()
          sourceSets {
            commonMain {
              dependencies {
                implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0'
              }
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('jvm')
    testProjectDir.newFile('jvm/build.gradle') <<
      """
        plugins {
          id 'java'
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
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

  private def report() {
    return new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
  }

  def 'Aggregates a multiplatform project consistently across the configuration cache'() {
    when:
    def aggregateRun = run(ARGUMENTS + ['--no-parallel'])
    def aggregated = report()
    run(ARGUMENTS + ['--parallel', '--configuration-cache'])
    def stored = report()
    def hitRun = run(ARGUMENTS + ['--parallel', '--configuration-cache'])
    def reused = report()

    then:
    aggregateRun.task(':kmp:dependencyUpdatesPartial').outcome == SUCCESS
    aggregateRun.task(':jvm:dependencyUpdatesPartial').outcome == SUCCESS
    hitRun.output.contains('Reusing configuration cache')
    !hitRun.output.contains('The dependency updates report is missing')
    aggregated.outdated.dependencies*.name.contains('kotlinx-coroutines-core')
    aggregated.outdated.dependencies*.name.contains('guice')
    stored == aggregated
    reused == aggregated
  }
}
