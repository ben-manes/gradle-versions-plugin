package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Requires
import spock.lang.Specification

@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/367')
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/720')
final class SettingsClasspathSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
    testProjectDir.newFile('build.gradle') << ''
  }

  private void settings(String body) {
    testProjectDir.newFile('settings.gradle') << body.stripIndent()
  }

  private def run(String... arguments) {
    return runner(arguments).build()
  }

  private def runOn(String gradleVersion, String... arguments) {
    return runner(arguments).withGradleVersion(gradleVersion).build()
  }

  private GradleRunner runner(String... arguments) {
    return GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(['dependencyUpdates'] + arguments.toList())
      .withPluginClasspath()
  }

  def 'Reports the dependencies of the settings buildscript'() {
    given:
    settings(
      """
        buildscript {
          repositories {
            maven {
              url = '${mavenRepoUrl}'
            }
          }

          dependencies {
            classpath 'com.example.settings-demo:com.example.settings-demo.gradle.plugin:1.0'
          }
        }

        plugins {
          id 'io.github.ben-manes.versions.settings'
        }
      """)

    when:
    def result = run()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
  }

  def 'Reports the plugins the settings script declares'() {
    given:
    settings(
      """
        pluginManagement {
          repositories {
            maven {
              url = '${mavenRepoUrl}'
            }
          }
        }

        plugins {
          id 'io.github.ben-manes.versions.settings'
          id 'com.example.settings-demo' version '1.0' apply false
        }
      """)

    when:
    def result = run()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
  }

  // Gradle 9 requires JVM 17.
  @Requires({ jvm.java17Compatible })
  def 'Reports the settings plugins under isolated projects on a configuration cache hit'() {
    given:
    settings(
      """
        pluginManagement {
          repositories {
            maven {
              url = '${mavenRepoUrl}'
            }
          }
        }

        plugins {
          id 'io.github.ben-manes.versions.settings'
          id 'com.example.settings-demo' version '1.0' apply false
        }
      """)
    def arguments = ['-Dorg.gradle.isolated-projects=true', '--configuration-cache'] as String[]
    def expected =
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]'

    when:
    def stored = runOn('9.7.0-rc-1', arguments)

    then:
    stored.task(':dependencyUpdates').outcome == SUCCESS
    stored.output.contains('Configuration cache entry stored')
    stored.output.contains(expected)

    when:
    def hit = runOn('9.7.0-rc-1', arguments)

    then:
    hit.task(':dependencyUpdates').outcome == SUCCESS
    hit.output.contains('Configuration cache entry reused')
    hit.output.contains(expected)
  }

  def 'Reports the settings plugins once in a multi-project build'() {
    given:
    settings(
      """
        pluginManagement {
          repositories {
            maven {
              url = '${mavenRepoUrl}'
            }
          }
        }

        plugins {
          id 'io.github.ben-manes.versions.settings'
          id 'com.example.settings-demo' version '1.0' apply false
        }

        include 'app'
      """)
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        plugins {
          id 'java'
        }

        repositories {
          maven {
            url = '${mavenRepoUrl}'
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
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    // Held by the settings, not by any project, so the accumulator reports it exactly once.
    result.output.count(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]') == 1
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/533')
  def 'Reports the merged result without a root build script'() {
    given:
    new File(testProjectDir.root, 'build.gradle').delete()
    settings(
      """
        pluginManagement {
          repositories {
            maven {
              url = '${mavenRepoUrl}'
            }
          }
        }

        plugins {
          id 'io.github.ben-manes.versions.settings'
          id 'com.example.settings-demo' version '1.0' apply false
        }

        include 'app'
      """)
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        plugins {
          id 'java'
        }

        repositories {
          maven {
            url = '${mavenRepoUrl}'
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
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
  }

  def 'Does not report a plugin pinned in pluginManagement but never applied'() {
    given:
    settings(
      """
        pluginManagement {
          repositories {
            maven {
              url = '${mavenRepoUrl}'
            }
          }

          plugins {
            id 'com.example.settings-demo' version '1.0'
          }
        }

        plugins {
          id 'io.github.ben-manes.versions.settings'
        }
      """)

    when:
    def result = run()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    // A pin is a constraint on whoever applies the plugin, not an applied plugin, so it never
    // reaches the settings classpath and reporting it would claim a dependency the build lacks.
    !result.output.contains('com.example.settings-demo')
  }

  def 'Reports nothing from the settings when the plugin is applied to the project only'() {
    given:
    settings(
      """
        buildscript {
          repositories {
            maven {
              url = '${mavenRepoUrl}'
            }
          }

          dependencies {
            classpath 'com.example.settings-demo:com.example.settings-demo.gradle.plugin:1.0'
          }
        }
      """)
    new File(testProjectDir.root, 'build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }
      """.stripIndent()

    when:
    def result = run()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains('com.example.settings-demo')
  }
}
