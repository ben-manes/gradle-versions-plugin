package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
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
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    !result.output.contains('The dependency updates report is missing')
  }

  def 'Reports a project declared explicitly only once'() {
    given:
    new File(testProjectDir.root, 'app/build.gradle') <<
      """
        dependencies {
          implementation project(':lib')
        }
      """.stripIndent()
    new File(testProjectDir.root, 'build.gradle') <<
      """
        dependencies {
          dependencyUpdatesAggregation project(':app')
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    // Every project is aggregated automatically, so declaring one is redundant rather than
    // narrowing, and the redundant declaration must not report it twice.
    result.output.count('com.google.guava:guava [15.0 -> 16.0-rc1]') == 1
  }

  def 'Aggregates in parallel with the configuration cache'() {
    when:
    def first = run(['dependencyUpdates', '--parallel', '--configuration-cache'])
    def second = run(['dependencyUpdates', '--parallel', '--configuration-cache'])

    then:
    first.task(':dependencyUpdates').outcome == SUCCESS
    second.task(':dependencyUpdates').outcome == SUCCESS
    second.output.contains('Reusing configuration cache')
    second.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    second.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    // The expected paths are task state, so a cache hit must not degrade to a false warning.
    !second.output.contains('The dependency updates report is missing')
  }

  // Gradle 9 requires JVM 17.
  @Requires({ jvm.java17Compatible })
  @Unroll
  def 'Aggregates in parallel on Gradle #gradleVersion'() {
    when:
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '--parallel', '--configuration-cache')
      .withPluginClasspath()
      .build()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')

    where:
    gradleVersion << ['9.0.0', '9.6.1']
  }

  def 'Reports every projects producer in the merged report'() {
    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=json', '--no-parallel'])
    def report = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':app:partialDependencyUpdates').outcome == SUCCESS
    result.task(':lib:partialDependencyUpdates').outcome == SUCCESS
    report.outdated.dependencies*.name.containsAll(['guice', 'guava'])
  }

  def 'Resolves at the revision given by a system property'() {
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        allprojects {
          dependencies {
            components {
              all { details ->
                if (details.id.version.contains('-rc')) {
                  details.status = 'milestone'
                }
              }
            }
          }
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=json', '-Drevision=release',
                      '--no-parallel'])
    def report = new File(testProjectDir.root, 'build/dependencyUpdates/report.json').text

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    // The release candidate is a milestone, so the release revision holds guava at 15.0. A producer
    // that resolved at the default revision instead would offer 16.0-rc1 as the later version.
    report.contains('"guava"')
    !report.contains('16.0-rc1')
  }

  def 'Aggregates sibling projects that share a group and name'() {
    given:
    new File(testProjectDir.root, 'settings.gradle').text =
      "include 'app', 'app:core', 'lib', 'lib:core'"
    new File(testProjectDir.root, 'build.gradle').text =
      """
        plugins {
          id 'com.github.ben-manes.versions'
        }

        allprojects {
          apply plugin: 'java'
          group = 'com.example'
          version = '1.0'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent()
    new File(testProjectDir.root, 'app/build.gradle').text = ''
    new File(testProjectDir.root, 'lib/build.gradle').text = ''
    new File(testProjectDir.root, 'app/core').mkdirs()
    new File(testProjectDir.root, 'app/core/build.gradle').text =
      """
        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    new File(testProjectDir.root, 'lib/core').mkdirs()
    new File(testProjectDir.root, 'lib/core/build.gradle').text =
      """
        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    // Task-output wiring bypasses module conflict resolution, so no partial is evicted.
    !result.output.contains('The dependency updates report is missing')
  }

  def 'Aggregates a configuration created after the project is evaluated'() {
    given:
    new File(testProjectDir.root, 'lib/build.gradle') <<
      """
        afterEvaluate {
          configurations {
            lateConfig {
              canBeResolved = true
              canBeConsumed = false
            }
          }
          dependencies {
            lateConfig 'com.google.inject:guice:2.2'
          }
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    result.output.contains('com.google.inject:guice [2.2 -> 3.1]')
  }

  def 'Honors a subproject task setting when it also applies the plugin'() {
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        subprojects {
          apply plugin: 'com.github.ben-manes.versions'
        }
      """.stripIndent()
    new File(testProjectDir.root, 'app/build.gradle') <<
      """
        dependencyUpdates {
          rejectVersionIf {
            it.candidate.version == '3.1'
          }
        }
      """.stripIndent()

    when:
    def result = run([':app:dependencyUpdates', '--no-parallel'])

    then:
    result.task(':app:dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
  }

  def 'Honors the configuration filter when the children evaluate first'() {
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        tasks.named('dependencyUpdates').configure {
          filterConfigurations { false }
        }

        evaluationDependsOnChildren()
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains('com.google.inject:guice')
    !result.output.contains('com.google.guava:guava')
  }

  def 'Resolves the aggregation results without traversing the projects dependencies'() {
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        tasks.register('dumpAggregationGraph') {
          def resolutionResult =
            configurations.aggregateDependencyUpdatesResults.incoming.resolutionResult
          doLast {
            resolutionResult.allDependencies.each {
              println "GRAPHEDGE \${it.class.simpleName} \${it.requested}"
            }
          }
        }
      """.stripIndent()

    when:
    def result = run(['dumpAggregationGraph', '--no-parallel'])

    then:
    result.task(':dumpAggregationGraph').outcome == SUCCESS
    !result.output.contains('UnresolvedDependencyResult')
  }
}
