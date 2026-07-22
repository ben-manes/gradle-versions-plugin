package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Requires
import spock.lang.Specification

// Gradle 9 requires JVM 17.
@Requires({ jvm.java17Compatible })
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
final class ContributorAggregationSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
    testProjectDir.newFile('settings.gradle') << "include 'app', 'lib'"
    // Only the root applies the reporting plugin, so 'dependencyUpdates' by name matches one task.
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'com.github.ben-manes.versions'
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        plugins {
          id 'java'
          id 'com.github.ben-manes.versions.contributor'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        plugins {
          id 'java'
          id 'com.github.ben-manes.versions.contributor'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()
  }

  private def run(String... arguments) {
    return GradleRunner.create()
      .withGradleVersion('9.7.0-rc-1')
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  def 'Aggregates by name under isolated projects with only the root reporting'() {
    when:
    def result = run('dependencyUpdates', '-Dorg.gradle.isolated-projects=true',
      '--configuration-cache', '--parallel')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    !result.output.contains('The dependency updates report is missing')
    // The contributor plugin registers no task with this name, so a bare invocation matches neither.
    result.task(':app:dependencyUpdates') == null
    result.task(':lib:dependencyUpdates') == null
  }

  def 'Honors the root task settings in every contributor producer'() {
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        dependencyUpdates.resolutionStrategy {
          componentSelection {
            all {
              if (candidate.version == '3.1') {
                reject('unstable')
              }
            }
          }
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-Dorg.gradle.isolated-projects=true',
      '--configuration-cache', '--parallel')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    // guice lives in the contributor-only :app project, so honoring 3.0 proves the reject reached it.
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
  }

  def 'Aggregates once when a project applies both plugins'() {
    given:
    new File(testProjectDir.root, 'app/build.gradle').text =
      """
        plugins {
          id 'java'
          id 'com.github.ben-manes.versions'
          id 'com.github.ben-manes.versions.contributor'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()

    when:
    def result = run(':dependencyUpdates', '-Dorg.gradle.isolated-projects=true',
      '--configuration-cache', '--parallel')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    // A shared partial producer means the doubly-applied project is reported exactly once.
    result.output.count('com.google.inject:guice [2.0 -> 3.1]') == 1
  }

  def 'Aggregates by name without isolated projects'() {
    when:
    def result = run('dependencyUpdates', '--configuration-cache', '--parallel')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    result.task(':app:dependencyUpdates') == null
    result.task(':lib:dependencyUpdates') == null
  }

  def 'Reuses the configuration cache across runs'() {
    when:
    run('dependencyUpdates', '-Dorg.gradle.isolated-projects=true',
      '--configuration-cache', '--parallel')
    def result = run('dependencyUpdates', '-Dorg.gradle.isolated-projects=true',
      '--configuration-cache', '--parallel')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('Configuration cache entry reused.')
  }

  def 'Fails to match dependencyUpdates when only the contributor plugin is applied'() {
    given:
    new File(testProjectDir.root, 'build.gradle').text =
      """
        plugins {
          id 'com.github.ben-manes.versions.contributor'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withGradleVersion('9.7.0-rc-1')
      .withProjectDir(testProjectDir.root)
      .withArguments(':dependencyUpdates')
      .withPluginClasspath()
      .buildAndFail()

    then:
    // The producer's name must not let task abbreviation resolve 'dependencyUpdates' to it, which
    // would run the internal task and succeed with no report.
    result.output.contains("Cannot locate tasks that match ':dependencyUpdates'")
  }
}
