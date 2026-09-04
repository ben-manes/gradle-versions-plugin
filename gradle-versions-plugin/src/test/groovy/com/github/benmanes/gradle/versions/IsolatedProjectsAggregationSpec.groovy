package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Requires
import spock.lang.Specification

// Gradle 9 requires JVM 17.
@Requires({ jvm.java17Compatible })
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
final class IsolatedProjectsAggregationSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
    testProjectDir.newFile('settings.gradle') << "include 'app', 'lib'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
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
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
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
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()
  }

  private def run(List<String> arguments = []) {
    return runWith(':dependencyUpdates', arguments)
  }

  private def runWith(String task, List<String> arguments = []) {
    return GradleRunner.create()
      .withGradleVersion(GradleVersions.CURRENT)
      .withProjectDir(testProjectDir.root)
      .withArguments([task, '-Dorg.gradle.isolated-projects=true',
        '--configuration-cache'] + arguments)
      .withPluginClasspath()
      .build()
  }

  def 'Aggregates when applied per project with isolated projects'() {
    when:
    def result = run()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
    !result.output.contains('The dependency updates report is missing')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1040')
  def 'Collects every projects partial result under the aggregating project'() {
    when:
    def result = run(['--clean-legacy-partials'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    // Guards the flag itself: the property is a silent no-op on a Gradle whose spelling differs
    // (pre-9.7 uses org.gradle.unsafe.isolated-projects), which would pass the non-isolated branch.
    result.output.contains('Isolated Projects is an incubating feature.')
    // The root publishes where the results are collected and each project's own producer reads it
    // from there, so isolated projects writes them where every other mode does.
    new File(testProjectDir.root, 'build/dependencyUpdates/partials').list().length == 3
    !new File(testProjectDir.root, 'build/dependencyUpdates/partial.json').exists()
    !new File(testProjectDir.root, 'app/build/dependencyUpdates/partial.json').exists()
    !new File(testProjectDir.root, 'lib/build/dependencyUpdates/partial.json').exists()
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1040')
  def 'Keeps its own partial result where the root project collects none'() {
    given:
    new File(testProjectDir.root, 'build.gradle').text = ''

    when:
    def result = runWith(':app:dependencyUpdates')

    then:
    result.task(':app:dependencyUpdates').outcome == SUCCESS
    result.output.contains('Isolated Projects is an incubating feature.')
    // Only the project at the root path publishes where the results are collected, as one that
    // aggregates from further down reads the projects it does not own as variant artifacts, for
    // which the file's location does not matter. Its own producer falls back to its build directory.
    new File(testProjectDir.root, 'app/build/dependencyUpdates/partial.json').exists()
    !new File(testProjectDir.root, 'build/dependencyUpdates/partials').exists()
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  def 'Honors the root task settings in every projects producer'() {
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
    def result = run()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
  }

  def 'Honors the root task settings applied from its own afterEvaluate'() {
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        afterEvaluate {
          tasks.named('dependencyUpdates').configure {
            rejectVersionIf {
              it.candidate.version == '3.1'
            }
          }
        }
      """.stripIndent()

    when:
    def result = run()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    // Isolated projects configures the projects in parallel, but a producer's input is realized
    // once the work graph is assembled, so a setting made this late still reaches the resolution.
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
  }

  def 'Omits and warns about a project that does not apply the plugin itself'() {
    given:
    new File(testProjectDir.root, 'lib/build.gradle').text =
      """
        plugins {
          id 'java'
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

    when:
    def result = run()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    // Isolated projects lets the root discover the project paths but not register a task in them,
    // so a project without the plugin publishes nothing to aggregate and is called out instead.
    // Registering the producers from a settings plugin is the only fix, which is a change to how
    // the plugin is applied.
    !result.output.contains('com.google.guava:guava')
    result.output.contains('The dependency updates report is missing :lib')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/801')
  def 'Prints the project of a skipped configuration with isolated projects'() {
    given:
    // The measured #801 trigger: a withModule id missing its ':name' half.
    new File(testProjectDir.root, 'app/build.gradle') <<
      '''
        dependencyUpdates.resolutionStrategy {
          componentSelection { rules ->
            rules.withModule('com.google.guava') { }
          }
        }
      '''.stripIndent()

    when:
    def result = run(['-DoutputFormatter=plain,json'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('Isolated Projects is an incubating feature.')
    // Only the project whose strategy throws loses its dependencies, so the other is still reported.
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
    !result.output.contains('com.google.inject:guice [')

    // Each project resolves in isolation and the aggregate takes its name from the partial result
    // it published, rather than from the project it was read into.
    def json = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    json.skipped.count > 0
    json.skipped.configurations.every { it.project == ':app' }
    json.skipped.configurations*.name.contains('compileClasspath')
  }

  def 'Omits a project that has no build script from the warning'() {
    given:
    new File(testProjectDir.root, 'settings.gradle').text =
      "include 'app', 'lib', 'container:nested'"
    testProjectDir.newFolder('container', 'nested')
    testProjectDir.newFile('container/nested/build.gradle') <<
      """
        plugins {
          id 'java'
          id 'io.github.ben-manes.versions'
        }
      """.stripIndent()

    when:
    def result = run()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    // A container project cannot apply the plugin without a build script of its own, so warning
    // about it would report what the user has no way to act on.
    !result.output.contains('The dependency updates report is missing')
  }
}
