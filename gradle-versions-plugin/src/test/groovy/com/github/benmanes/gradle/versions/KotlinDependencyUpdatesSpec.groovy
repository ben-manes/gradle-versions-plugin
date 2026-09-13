package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.See
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

final class KotlinDependencyUpdatesSpec extends Specification {
  private static final DECLARED_KOTLIN_VERSION = '1.7.0'
  private static final DECLARED_KOTLIN_STD_VERSION = '1.8.0'
  // A release that runs on Gradle 9, where the declared versions above do not, and old enough that
  // what it contributes is still reported with an upgrade available.
  private static final APPLIED_KOTLIN_VERSION = '2.2.20'

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
            id 'io.github.ben-manes.versions' version '0.51.0'
            id 'java-gradle-plugin'
            ${applyJvmPlugin ? "id 'org.jetbrains.kotlin.jvm' version '$APPLIED_KOTLIN_VERSION'" : ''}
        }

        repositories {
            mavenCentral()
        }

        dependencies {
            implementation "org.jetbrains.kotlin:kotlin-gradle-plugin:\$kotlin_version"
        }
      """.stripIndent()

    testProjectDir.newFile('gradle.properties') << "kotlin_version = $DECLARED_KOTLIN_VERSION"

    when:
    def result = TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('The following dependencies have later milestone versions:')
    // The version on the classpath is the one the buildscript declares, not the applied plugin's.
    result.output.find(
      / - org\.jetbrains\.kotlin:kotlin-gradle-plugin \[$DECLARED_KOTLIN_VERSION -> 2\..*\]/)
    !applyJvmPlugin ||
      result.output.find(
        / - org\.jetbrains\.kotlin:kotlin-compiler-embeddable \[$APPLIED_KOTLIN_VERSION -> 2\..*\]\n\s+https:\/\/kotlinlang\.org\/\n\s+contributed by a plugin into the 'kotlinCompilerClasspath' configuration\n/)
    // Contributed via the plugin's own defaultDependencies action, unlike the classpath's own
    // kotlin-gradle-plugin. The Kotlin 2 plugin contributes kotlin-stdlib, where the Kotlin 1
    // plugin contributed kotlin-stdlib-jdk8.
    !applyJvmPlugin ||
      result.output.find(
        / - org\.jetbrains\.kotlin:kotlin-stdlib \[$APPLIED_KOTLIN_VERSION -> 2\..*\]\n\s+https:\/\/kotlinlang\.org\/\n\s+contributed by a plugin into the 'api' configuration/)
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
            id 'io.github.ben-manes.versions' version '0.51.0'
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

    testProjectDir.newFile('gradle.properties') << "kotlin_version = $DECLARED_KOTLIN_VERSION"

    when:
    def result = TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.find(/The following dependencies have later milestone versions:\n - org\.jetbrains\.kotlin:kotlin-gradle-plugin \[$DECLARED_KOTLIN_VERSION -> 2\..*\]/)
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @See("https://github.com/ben-manes/gradle-versions-plugin/issues/423")
  def "kotlin stdlib is properly handled (when added explicitly: #explicitStdLibVersion)"() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
            id 'io.github.ben-manes.versions' version '0.51.0'
            id 'org.jetbrains.kotlin.jvm' version '$APPLIED_KOTLIN_VERSION'
        }

        repositories {
            mavenCentral()
        }

        dependencies {
            implementation "org.jetbrains.kotlin:kotlin-stdlib${explicitStdLibVersion ? ":$DECLARED_KOTLIN_STD_VERSION" : ""}"
        }
      """.stripIndent()

    when:
    def result = TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '--info')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('The following dependencies have later milestone versions:')
    // The compiler and klib artifacts are contributed by the plugin's own defaultDependencies
    // action, while the scripting compiler is added eagerly.
    result.output.find(/ - org\.jetbrains\.kotlin:kotlin-compiler-embeddable \[$APPLIED_KOTLIN_VERSION -> 2\..*\]\n\s+https:\/\/kotlinlang\.org\/\n\s+contributed by a plugin into the 'kotlinCompilerClasspath' configuration\n/)
    result.output.find(/ - org\.jetbrains\.kotlin:kotlin-klib-commonizer-embeddable \[$APPLIED_KOTLIN_VERSION -> 2\..*\]\n\s+https:\/\/kotlinlang\.org\/\n\s+contributed by a plugin into the 'kotlinKlibCommonizerClasspath' configuration\n/)
    result.output.find(/ - org\.jetbrains\.kotlin:kotlin-scripting-compiler-embeddable \[$APPLIED_KOTLIN_VERSION -> 2\..*\]\n\s+https:\/\/kotlinlang\.org\/\n\s+declared in the 'kotlinCompilerPluginClasspathMain' and 'kotlinCompilerPluginClasspathTest' configurations\n/)
    // kotlin-stdlib is the build's own declaration, whether or not it names a version, so the
    // line after it is the next entry rather than an attribution.
    result.output.find(/ - org\.jetbrains\.kotlin:kotlin-stdlib \[${explicitStdLibVersion ? DECLARED_KOTLIN_STD_VERSION : APPLIED_KOTLIN_VERSION} -> 2\..*\]\n\s+https:\/\/kotlinlang\.org\/\n(?!\s+contributed|\s+declared)/)
    result.output.find(/ - org\.jetbrains\.kotlin\.jvm:org\.jetbrains\.kotlin\.jvm\.gradle\.plugin \[$APPLIED_KOTLIN_VERSION -> 2\..*\]\n\s+https:\/\/kotlinlang\.org\/\n/)
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    explicitStdLibVersion << [true, false]
  }
}
