package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.See
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

final class PluginUpdateDetectionSpec extends Specification {
  private static final KOTLIN_VERSION = '1.6.0'

  @Rule
  final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  @See("https://github.com/ben-manes/gradle-versions-plugin/discussions/823")
  def "kotlin plugin in the classpath configuration is properly handled (applying JVM plugin: #applyJvmPlugin)"() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
            dependencies {
                classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:\$kotlin_version"
            }
        }

        plugins {
            id 'com.github.ben-manes.versions' version '0.50.0'
            id 'java-gradle-plugin'
            ${applyJvmPlugin ? "id 'org.jetbrains.kotlin.jvm' version \"\$kotlin_version\"" : ''}
        }

        repositories {
            mavenCentral()
        }

        dependencies {
            implementation "org.jetbrains.kotlin:kotlin-gradle-plugin:\$kotlin_version"
        }
      """.stripIndent()

    testProjectDir.newFile('gradle.properties') << "kotlin_version = $KOTLIN_VERSION"

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains """The following dependencies have later milestone versions:
 - org.jetbrains.kotlin:kotlin-gradle-plugin [$KOTLIN_VERSION -> """
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    applyJvmPlugin << [true, false]
  }

  @See("https://github.com/ben-manes/gradle-versions-plugin/discussions/823")
  def "kotlin plugin in the implementation configuration is properly handled"() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
            id 'com.github.ben-manes.versions' version '0.46.0'
            id 'java-gradle-plugin'
        }

        repositories {
            mavenCentral()
        }

        dependencies {
            implementation "org.jetbrains.kotlin:kotlin-stdlib:\$kotlin_version"
            implementation "org.jetbrains.kotlin:kotlin-gradle-plugin:\$kotlin_version"
        }
      """.stripIndent()

    testProjectDir.newFile('gradle.properties') << "kotlin_version = $KOTLIN_VERSION"

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains """The following dependencies have later milestone versions:
 - org.jetbrains.kotlin:kotlin-gradle-plugin [$KOTLIN_VERSION -> """
    result.task(':dependencyUpdates').outcome == SUCCESS
  }
}
