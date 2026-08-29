package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

final class TaskOptionSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  private File written

  /** Fails with the reason rather than an IOException when a spec writes two root build files. */
  private File rootBuildFile() {
    if (written != null) {
      throw new IllegalStateException('The root build file was already written by this spec')
    }
    written = testProjectDir.newFile('build.gradle')
    return written
  }

  private void buildFile(String configured = '', String buildscript = '') {
    rootBuildFile() <<
      """
        ${buildscript}

        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        ${configured}

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()
  }

  private void declaringBuildFile(String configured = '') {
    rootBuildFile() <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        ${configured}

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
  }

  private def run(String... arguments) {
    return GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  def 'Reports a constrained version when the check is turned on from the command line'() {
    given:
    buildFile()

    when:
    def result = run('dependencyUpdates', '--check-constraints')

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Omits a constrained version when the command line turns off what is configured in the build'() {
    given:
    buildFile('tasks.dependencyUpdates { checkConstraints = true }')

    when:
    def result = run('dependencyUpdates', '--no-check-constraints')

    then:
    !result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Reports a build environment constraint when the check is turned on from the command line'() {
    given:
    buildFile(
      '',
      """
        buildscript {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
          dependencies {
            constraints {
              classpath 'com.google.guava:guava:15.0'
            }
          }
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates', '--check-build-environment-constraints')

    then:
    result.output.contains('com.google.guava:guava [15.0 -> ')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Omits the Gradle release check when the command line turns it off'() {
    given:
    buildFile()

    when:
    def result = run('dependencyUpdates', '--no-check-for-gradle-update')

    then:
    !result.output.contains(' - Gradle: [')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Reads the Gradle versions api from the base url given on the command line'() {
    given:
    buildFile()

    when:
    def result = run(
      'dependencyUpdates', '--gradle-versions-api-base-url', 'http://127.0.0.1:1/versions/')

    then:
    result.output.contains('[ERROR] [release channel: current]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Resolves against the revision level given on the command line'() {
    given:
    declaringBuildFile()

    when:
    def result = run('dependencyUpdates', '--revision', 'release')

    then:
    result.output.contains('release version')
    !result.output.contains('Failed to determine the latest version')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Resolves against the command line revision ahead of the system property'() {
    given:
    declaringBuildFile()

    when: 'the revision in the system property is one the producer can resolve against'
    def result = run('dependencyUpdates', '-Drevision=release', '--revision', 'bogus')

    then: 'the producer resolved against the command line, so it found no version at all'
    result.output.contains('Failed to determine the latest version')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Resolves against the command line revision ahead of what is configured in the build'() {
    given:
    declaringBuildFile("tasks.dependencyUpdates { revision = 'bogus' }")

    when:
    def result = run('dependencyUpdates', '--revision', 'release')

    then:
    !result.output.contains('Failed to determine the latest version')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Reports the Gradle releases of the channel given on the command line'() {
    given:
    declaringBuildFile()

    when: 'the api is unreachable, so an error is printed for each release channel consulted'
    def result = run(
      'dependencyUpdates',
      '--gradle-release-channel', 'nightly',
      '--gradle-versions-api-base-url', 'http://127.0.0.1:1/versions/')

    then:
    result.output.contains('[release channel: nightly]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Writes the report to the directory given on the command line, ahead of the system property'() {
    given:
    declaringBuildFile()

    when:
    def result = run(
      'dependencyUpdates',
      '-DreportfileName=fromSystemProperty',
      '-DoutputDir=build/fromSystemProperty',
      '--report-file-name', 'fromCommandLine',
      '--output-dir', 'build/fromCommandLine')

    then:
    new File(testProjectDir.root, 'build/fromCommandLine/fromCommandLine.txt').exists()
    !new File(testProjectDir.root, 'build/fromSystemProperty').exists()
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Writes the report in the format given on the command line, ahead of the system property'() {
    given:
    declaringBuildFile()

    when:
    def result = run(
      'dependencyUpdates',
      '-DoutputFormatter=xml',
      '--output-formatter', 'json',
      '--output-dir', 'build/formatted')

    then:
    new File(testProjectDir.root, 'build/formatted/report.json').exists()
    !new File(testProjectDir.root, 'build/formatted/report.xml').exists()
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Reports a constrained version for a subproject with the check turned off on its own task'() {
    given: 'the subproject has its own task settings, which its producers inherit from'
    testProjectDir.newFile('settings.gradle') << "include 'lib'"
    rootBuildFile() <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          apply plugin: 'java-library'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = false
        }

        dependencies {
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()

    when: 'the option is given to the aggregating task alone, by path'
    def result = run(':dependencyUpdates', '--check-constraints')

    then: 'the option applies to the subproject, which its own configured value would outrank'
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Omits a build environment constraint when the command line turns the check off'() {
    given:
    buildFile(
      'tasks.dependencyUpdates { checkBuildEnvironmentConstraints = true }',
      """
        buildscript {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
          dependencies {
            constraints {
              classpath 'com.google.guava:guava:15.0'
            }
          }
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates', '--no-check-build-environment-constraints')

    then:
    !result.output.contains('com.google.guava:guava [15.0 -> ')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Reads the option rather than the entry cached under the opposite one'() {
    given:
    buildFile()

    when: 'the entry is stored with the check on, then the opposite is asked for'
    run('dependencyUpdates', '--check-constraints', '--configuration-cache')
    def reused = run('dependencyUpdates', '--check-constraints', '--configuration-cache')
    def off = run('dependencyUpdates', '--no-check-constraints', '--configuration-cache')

    then:
    reused.output.contains('Reusing configuration cache')
    reused.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    !off.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  def 'Resolves against the system property ahead of what is configured in the build'() {
    given: 'a revision the producers cannot resolve against, configured in the build'
    declaringBuildFile("tasks.dependencyUpdates { revision = 'bogus' }")

    when: 'the revision in the system property is one they can, with no option in play'
    def result = run('dependencyUpdates', '-Drevision=release')

    then:
    !result.output.contains('Failed to determine the latest version')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Writes the report under the file name given as a system property, ahead of the build'() {
    given:
    declaringBuildFile("tasks.dependencyUpdates { reportfileName = 'fromBuild' }")

    when:
    def result = run('dependencyUpdates', '-DreportfileName=fromSystemProperty')

    then:
    new File(testProjectDir.root, 'build/dependencyUpdates/fromSystemProperty.txt').exists()
    !new File(testProjectDir.root, 'build/dependencyUpdates/fromBuild.txt').exists()
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Turns off the Gradle release check from the command line, against the configured value'() {
    given:
    declaringBuildFile('tasks.dependencyUpdates { checkForGradleUpdate = true }')

    when:
    def result = run('dependencyUpdates', '--no-check-for-gradle-update')

    then:
    !result.output.contains(' - Gradle: [')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Reads the versions api from the command line url, ahead of the one configured in the build'() {
    given:
    declaringBuildFile(
      "tasks.dependencyUpdates { gradleVersionsApiBaseUrl = 'https://services.gradle.org/versions/' }")

    when:
    def result = run(
      'dependencyUpdates', '--gradle-versions-api-base-url', 'http://127.0.0.1:1/versions/')

    then:
    result.output.contains('[ERROR] [release channel: current]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Reports a build environment constraint for a subproject with it turned off on its own task'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'lib'"
    rootBuildFile() <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          apply plugin: 'java-library'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        buildscript {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
          dependencies {
            constraints {
              classpath 'com.google.guava:guava:15.0'
            }
          }
        }

        plugins {
          id 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkBuildEnvironmentConstraints = false
        }
      """.stripIndent()

    when:
    def result = run(':dependencyUpdates', '--check-build-environment-constraints')

    then:
    result.output.contains('com.google.guava:guava [15.0 -> ')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Keeps the command line value against a write made once the task graph is ready'() {
    given: 'a write that lands after Gradle has applied the command line options'
    buildFile('gradle.taskGraph.whenReady { tasks.dependencyUpdates.checkForGradleUpdate = true }')

    when:
    def result = run('dependencyUpdates', '--no-check-for-gradle-update')

    then:
    !result.output.contains(' - Gradle: [')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Keeps the constraints check against a write made once the task graph is ready'() {
    given:
    buildFile('gradle.taskGraph.whenReady { tasks.dependencyUpdates.checkConstraints = false }')

    when:
    def result = run('dependencyUpdates', '--check-constraints', '--no-check-for-gradle-update')

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Resolves an included build merged into the report with that build\'s own settings'() {
    given: 'one report merging an included build, each build applying the plugin'
    def classpath = getClass().classLoader.getResource('plugin-classpath.txt').readLines()
      .collect { it.replace('\\', '\\\\') }.collect { "'$it'" }.join(', ')
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    rootBuildFile() <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files(${classpath})
          }
        }

        apply plugin: 'java-library'
        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.guava:guava:15.0'
        }
      """.stripIndent()

    when: 'a revision no producer can resolve against is given on the command line'
    def viaOption = run('dependencyUpdates', '--revision', 'bogus')

    then: 'the invoking build resolves against it and the included build keeps its own'
    !viaOption.output.contains('com.google.inject:guice [2.0 ->')
    viaOption.output.contains('com.google.guava:guava [15.0 ->')

    when: 'the same revision is given as a system property, which is set for the whole JVM'
    def viaSystemProperty = run('dependencyUpdates', '-Drevision=bogus')

    then: 'both builds resolve against it'
    !viaSystemProperty.output.contains('com.google.inject:guice [2.0 ->')
    !viaSystemProperty.output.contains('com.google.guava:guava [15.0 ->')
  }

  @Unroll
  def 'Binds the options under Gradle #gradleVersion'() {
    given: 'the plugin on the buildscript classpath, so a pinned Gradle runs it'
    def classpath = getClass().classLoader.getResource('plugin-classpath.txt').readLines()
      .collect { it.replace('\\', '\\\\') }.collect { "'$it'" }.join(', ')
    rootBuildFile() <<
      """
        buildscript {
          dependencies {
            classpath files(${classpath})
          }
        }

        apply plugin: 'java-library'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()

    when: 'the option turns the check on, and its --no- counterpart turns it off again'
    def on = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '--check-constraints', '--no-check-for-gradle-update')
      .build()
    def off = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '--no-check-constraints', '--no-check-for-gradle-update')
      .build()

    then: 'the internal method the option binds to is reached at both ends of the range'
    on.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    !off.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    on.task(':dependencyUpdates').outcome == SUCCESS

    where:
    gradleVersion << ['8.4', '9.7.1']
  }

  def 'Prints every command line option the task accepts'() {
    given:
    buildFile()

    when:
    def result = run('help', '--task', 'dependencyUpdates')

    then:
    def output = result.output
    output.contains('--check-constraints')
    output.contains('--no-check-constraints')
    output.contains('--check-build-environment-constraints')
    output.contains('--check-for-gradle-update')
    output.contains('--gradle-versions-api-base-url')
    output.contains('--revision')
    output.contains('--gradle-release-channel')
    output.contains('--output-dir')
    output.contains('--report-file-name')
    output.contains('--clean-legacy-partials')
    output.contains('Available values are:')
    ['release', 'milestone', 'integration'].every { output.contains(it) }
    ['current', 'release-candidate', 'nightly'].every { output.contains(it) }
    output.contains('--output-formatter')
  }
}
