package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.IgnoreIf
import spock.lang.Issue
import spock.lang.Specification

@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1151')
final class SettingsScriptPluginIdSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()

  def 'setup'() {
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    testProjectDir.newFile('build.gradle')
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
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
  }

  private void settingsApplying(String pluginId) {
    testProjectDir.newFile('settings.gradle') <<
      """
        plugins {
          id '${pluginId}'
        }

        include 'app'

        println "versions plugin applied: \${pluginManager.hasPlugin('io.github.ben-manes.versions')}"
      """.stripIndent()
  }

  private def runner(String gradleVersion) {
    return TestKitRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments(':dependencyUpdates')
      .withPluginClasspath()
  }

  // Gradle 9 requires JVM 17.
  @IgnoreIf({ !GradleVersions.drivenBy(data.gradleVersion) })
  def 'Aggregates every project when #pluginId is applied in a settings script under Gradle #gradleVersion'() {
    given:
    settingsApplying(pluginId)

    when:
    def result = runner(gradleVersion).build()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 2.2 -> 3.1]')
    !result.output.contains('The dependency updates report is missing')

    and: 'hasPlugin is true for the versions plugin id under either id'
    result.output.contains('versions plugin applied: true')

    where:
    [pluginId, gradleVersion] << [
      ['io.github.ben-manes.versions', 'io.github.ben-manes.versions.settings'],
      ['8.4', GradleVersions.CURRENT],
    ].combinations()
  }

  // Gradle 9 requires JVM 17.
  @IgnoreIf({ !GradleVersions.drivenBy(GradleVersions.CURRENT) })
  def 'Rejects the deprecated plugin id in a settings script'() {
    given:
    settingsApplying('com.github.ben-manes.versions')

    when:
    def result = runner(GradleVersions.CURRENT).buildAndFail()

    then:
    result.output.contains('must be applied in a build script')
  }
}
