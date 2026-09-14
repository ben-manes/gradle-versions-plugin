package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.IgnoreIf
import spock.lang.Issue
import spock.lang.Specification

final class SharedClasspathSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()

  // The Kotlin Gradle Plugin 1.9.10 that `kotlin-dsl` applies on Gradle 8.4, the oldest supported
  // release, fails when a dependency on the same classpath raises its compiler modules to a newer Kotlin.
  // Gradle 8.4 fails to start on JDK 25, and its repository is built only for the older JDKs.
  @IgnoreIf({ jvm.java22Compatible })
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1112')
  def 'the published plugin is applied beside kotlin-dsl on Gradle 8.4'() {
    given:
    def repository = new File(systemProperty('specsRepository')).absoluteFile.toURI()
    testProjectDir.newFile('settings.gradle.kts') <<
      """
        pluginManagement {
          repositories {
            maven(url = "${repository}")
            gradlePluginPortal()
          }
        }

        rootProject.name = "consumer"
      """.stripIndent()
    testProjectDir.newFile('build.gradle.kts') <<
      """
        plugins {
          `kotlin-dsl`
          id("${systemProperty('pluginId')}") version "${systemProperty('pluginVersion')}"
        }
      """.stripIndent()

    when:
    def result = TestKitRunner.create()
      .withGradleVersion('8.4')
      .withProjectDir(testProjectDir.root)
      .withArguments('help')
      .build()

    then:
    result.task(':help').outcome == SUCCESS
  }

  private static String systemProperty(String name) {
    return Objects.requireNonNull(System.getProperty(name), "System property '${name}' is not set; run the spec from a testOn task")
  }
}
