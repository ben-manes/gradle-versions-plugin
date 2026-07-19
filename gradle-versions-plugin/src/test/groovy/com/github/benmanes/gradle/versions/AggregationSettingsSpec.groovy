package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Pins three settings-plumbing gaps in the aggregation topology's per-project parameter
 * resolution ({@code Aggregation.kt}). Kept separate from {@link AggregationSpec}, which covers
 * the topology's wiring and result correctness rather than its settings resolution.
 */
final class AggregationSettingsSpec extends Specification {
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

  def 'Honors dependencyUpdates configuration added from the root build script own afterEvaluate'() {
    // The plugin's own project.afterEvaluate (which freezes the task settings for the per-project
    // producers) is registered during `apply`, before the root build script body runs. A root
    // afterEvaluate written in the build script therefore registers, and so also runs, after the
    // freeze -- so the aggregate topology must not silently drop what it configures. The legacy
    // topology has no such freeze moment: it reads the task at execution time, so it is a valid
    // oracle for what the setting should have done.
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        afterEvaluate {
          tasks.named('dependencyUpdates').configure {
            rejectVersionIf {
              it.candidate.version == '16.0-rc1'
            }
          }
        }
      """.stripIndent()
    def arguments = ['dependencyUpdates', '-DoutputFormatter=json', '--no-parallel']

    when:
    def legacyRun = run(arguments)
    def legacy = new File(testProjectDir.root, 'build/dependencyUpdates/report.json').text
    def aggregateRun = run(arguments + ['-Dcom.github.benmanes.versions.aggregate=true'])
    def aggregated = new File(testProjectDir.root, 'build/dependencyUpdates/report.json').text

    then:
    legacyRun.task(':dependencyUpdates').outcome == SUCCESS
    aggregateRun.task(':dependencyUpdates').outcome == SUCCESS
    // The legacy report holds guava at 15.0: the root afterEvaluate's rejectVersionIf reached it.
    legacy.contains('"guava"') && !legacy.contains('16.0-rc1')
    // The aggregate report must agree, but today silently ignores the post-freeze configuration.
    aggregated == legacy
  }

  def 'Honors an explicit subproject setting that equals the default over a non-default ancestor'() {
    // DependencyUpdatesParameters.isDefault (Aggregation.kt) treats an explicitly-set value that
    // happens to equal the default as "unconfigured". A subproject that explicitly opts out of a
    // property an ancestor turned on must keep its own explicit value, not the ancestor's.
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        subprojects {
          apply plugin: 'com.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    new File(testProjectDir.root, 'app/build.gradle') <<
      """
        dependencyUpdates {
          checkConstraints = false
        }

        dependencies {
          constraints {
            implementation 'com.google.guava:guava:15.0'
          }
        }
      """.stripIndent()

    when:
    def result = run([':app:dependencyUpdates', '--no-parallel',
                      '-Dcom.github.benmanes.versions.aggregate=true'])

    then:
    result.task(':app:dependencyUpdates').outcome == SUCCESS
    // app has no explicit guava dependency, only the constraint, so it must only appear if app's
    // own checkConstraints = false is honored instead of falling back to the root's true.
    !result.output.contains('com.google.guava:guava')
  }

  def 'Honors an ancestor setting the subproject never touched, alongside its own explicit override'() {
    // DependencyUpdatesParametersService.resolve (Aggregation.kt) returns the nearest non-default
    // ancestor's ENTIRE record. A subproject that sets one property must keep the root's value for
    // every OTHER property it left untouched, not silently reset those to the framework defaults.
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        subprojects {
          apply plugin: 'com.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
          rejectVersionIf {
            it.candidate.version == '3.1'
          }
        }
      """.stripIndent()
    new File(testProjectDir.root, 'app/build.gradle') <<
      """
        dependencyUpdates {
          rejectVersionIf {
            it.candidate.version == '3.0'
          }
        }

        dependencies {
          constraints {
            implementation 'com.google.guava:guava:15.0'
          }
        }
      """.stripIndent()

    when:
    def result = run([':app:dependencyUpdates', '--no-parallel',
                      '-Dcom.github.benmanes.versions.aggregate=true'])

    then:
    result.task(':app:dependencyUpdates').outcome == SUCCESS
    // Half 1: app's own resolutionStrategy (rejecting 3.0) must win over the root's (rejecting
    // 3.1) -- if the root's rejection applied instead, guice would cap at 3.0, not 3.1.
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    // Half 2: app never touched checkConstraints, so it must still inherit the root's true --
    // app has no explicit guava dependency, only the constraint.
    result.output.contains('com.google.guava:guava')
  }

  @Unroll
  def 'Reads back an assigned resolutionStrategy closure on the #topology topology'() {
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        dependencyUpdates {
          resolutionStrategy = {
            componentSelection {
              all { selection ->
                if (selection.candidate.version == '3.1') {
                  selection.reject('rejected')
                }
              }
            }
          }
        }

        tasks.register('readBack') {
          def assigned = tasks.dependencyUpdates.resolutionStrategy != null
          doLast {
            println "readBack=\$assigned"
          }
        }
      """.stripIndent()

    when:
    def result = run([':readBack', ':dependencyUpdates', '--no-parallel'] + arguments)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    // Assigning the property must not clear it, or build logic guarding on "did anyone configure
    // this?" silently applies its own strategy over the user's.
    result.output.contains('readBack=true')
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')

    where:
    arguments << [[], ['-Dcom.github.benmanes.versions.aggregate=true']]
    topology = arguments ? 'aggregate' : 'legacy'
  }
}
