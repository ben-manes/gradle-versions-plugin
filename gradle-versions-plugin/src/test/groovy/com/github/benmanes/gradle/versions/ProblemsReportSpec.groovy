package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.IgnoreIf
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

/**
 * A specification for the report sent to Gradle's Problems API, which is available from Gradle 8.13.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/974')
final class ProblemsReportSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  @IgnoreIf({ !GradleVersions.drivenBy(GradleVersions.CURRENT) })
  @Unroll
  def 'Each outdated dependency is reported as a problem #description'() {
    given:
    writeBuild("implementation 'com.example:tiered-widget:1.0.1'")

    when: 'the build runs twice, so that the second run reuses a configuration cache entry'
    def results = (1..2).collect {
      run(GradleVersions.CURRENT, ['dependencyUpdates', '--warning-mode', 'all'] + arguments)
    }

    then:
    results.every { result ->
      result.task(':dependencyUpdates').outcome == SUCCESS &&
        result.output.contains(
          """Problem found: Outdated dependency (id: dependency-updates:com.example:tiered-widget)
          |  com.example:tiered-widget [1.0.1 -> 1.0.2 -> 1.1.1 -> 2.1.0 -> 2.2.0-rc1]
          |    Possible solutions:
          |      1. Upgrade com.example:tiered-widget to 1.0.2, the latest patch version.
          |      2. Upgrade com.example:tiered-widget to 1.1.1, the latest minor version.
          |      3. Upgrade com.example:tiered-widget to 2.1.0, the latest version.
          |      4. Upgrade com.example:tiered-widget to 2.2.0-rc1, the latest pre-release.""".stripMargin()) &&
        result.output.count('Problem found:') == 1
    }
    results[1].output.contains('Configuration cache entry reused.') == reused

    and: 'no report file is written for it'
    !new File(testProjectDir.root, 'build/dependencyUpdates/report.txt').exists()

    where:
    description                     | arguments                 || reused
    ''                              | []                        || false
    'under the configuration cache' | ['--configuration-cache'] || true
  }

  @IgnoreIf({ !GradleVersions.drivenBy(GradleVersions.CURRENT) })
  @Unroll
  def 'A dependency at #version is reported with an upgrade to each later version printed'() {
    given:
    writeBuild("implementation 'com.example:tiered-widget:$version'")

    when:
    def result = run(GradleVersions.CURRENT, ['dependencyUpdates', '--warning-mode', 'all'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains("com.example:tiered-widget $steps")
    solutions.every { result.output.contains("Upgrade com.example:tiered-widget to $it.") }
    result.output.count('Upgrade com.example:tiered-widget') == solutions.size()

    where: 'a version that is the latest of several tiers is described by the last of them'
    version || steps                          | solutions
    '2.1.0' || '[2.1.0 -> 2.2.0-rc1]'         | ['2.2.0-rc1, the latest pre-release']
    '2.0.0' || '[2.0.0 -> 2.1.0 -> 2.2.0-rc1]' | ['2.1.0, the latest version', '2.2.0-rc1, the latest pre-release']
  }

  @IgnoreIf({ !GradleVersions.drivenBy(GradleVersions.CURRENT) })
  def 'The lines printed under a row of the text report are included in its problem'() {
    given: 'two subprojects that declare the same version'
    testProjectDir.newFile('settings.gradle') << "include 'app', 'lib'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        subprojects {
          apply plugin: 'java'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }

          dependencies {
            implementation('com.google.inject:guice:2.0') {
              because 'kept for the spec'
            }
          }
        }

        dependencyUpdates {
          checkForGradleUpdate = false
          outputFormatter = 'problems'
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFolder('lib')

    when:
    def result = run(GradleVersions.CURRENT, [':dependencyUpdates', '--warning-mode', 'all'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      """  com.google.inject:guice [2.0 -> 2.2 -> 3.1]
      |    kept for the spec
      |    https://code.google.com/p/google-guice/
      |    declared in :app, :lib
      |    Possible solutions:""".stripMargin())
  }

  @IgnoreIf({ !GradleVersions.drivenBy(GradleVersions.CURRENT) })
  def 'Each outdated dependency is reported past the number of problems Gradle keeps for one id'() {
    given: 'two outdated dependencies, with Gradle keeping one problem for each id'
    writeBuild("""
      implementation 'com.google.inject:guice:2.0'
      implementation 'com.example:tiered-widget:1.0.1'
    """)

    when:
    def result = run(GradleVersions.CURRENT,
      ['dependencyUpdates', '--warning-mode', 'all', '-Dorg.gradle.internal.problem.summary.threshold=1'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.count('Problem found:') == 2
  }

  @IgnoreIf({ !GradleVersions.drivenBy('8.4') })
  def 'The problems are skipped on a Gradle release without the Problems API'() {
    given:
    writeBuild("implementation 'com.example:tiered-widget:1.0.1'")

    when:
    def result = run('8.4', ['dependencyUpdates', '--info'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('The problems report was skipped, as it needs Gradle 8.13 or later')
    !new File(testProjectDir.root, 'build/dependencyUpdates/report.txt').exists()
  }

  private void writeBuild(String declaration) {
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          $declaration
          implementation 'com.google.inject.extensions:guice-multibindings:3.0'
        }

        dependencyUpdates {
          checkForGradleUpdate = false // future proof tests from breaking
          outputFormatter = 'problems'
        }
      """.stripIndent()
  }

  private def run(String gradleVersion, List<String> arguments) {
    return TestKitRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }
}
