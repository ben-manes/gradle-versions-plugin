package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

/**
 * A specification for the configuration filters: {@code filterConfigurations} over the
 * configurations the task checks, and {@code filterDeclaredConfigurations} over the names a
 * report entry shows.
 */
final class ConfigurationFilterSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  /**
   * A 'toolBucket' that cannot be resolved, filled by {@code defaultDependencies} and reached
   * through the resolvable 'toolClasspath' that extends it, as a declare/resolve plugin pairs them.
   */
  private static String bucketConfigurations(
    String taskBody = '',
    String coordinate = 'com.google.guava:guava:15.0') {
    """
      configurations.create('toolBucket') {
        canBeResolved = false
        canBeConsumed = false
      }
      configurations.create('toolClasspath') {
        canBeResolved = true
        canBeConsumed = false
        extendsFrom configurations.toolBucket
      }
      configurations.toolBucket.defaultDependencies { deps ->
        deps.add(project.dependencies.create('$coordinate'))
      }

      dependencyUpdates {
        $taskBody
      }
    """.stripIndent()
  }

  private void writeBuild(String projectBody) {
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        $projectBody

        dependencyUpdates {
          checkForGradleUpdate = false
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

  def 'Names a configuration a plugin filled but cannot resolve'() {
    given:
    writeBuild(bucketConfigurations())

    when:
    def result = run(['dependencyUpdates'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(" - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}" +
      "     contributed by a plugin into the 'toolBucket' configuration")
  }

  def 'Keeps the entry when filterConfigurations rejects the configuration it names'() {
    given:
    writeBuild(bucketConfigurations("filterConfigurations { it.name != 'toolBucket' }"))

    when:
    def result = run(['dependencyUpdates'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(" - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}" +
      "     contributed by a plugin into the 'toolBucket' configuration")
  }

  def 'Leaves out the entry when filterConfigurations rejects the configuration that resolves it'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        dependencies {
          implementation 'com.google.inject:guice:3.1'
        }

        ${bucketConfigurations("filterConfigurations { it.name != 'toolClasspath' }")}
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains('com.google.guava:guava')
    // The rejection removes the entry rather than emptying the report.
    result.output.contains('com.google.inject:guice')
  }

  def 'Keeps the entry when another resolvable configuration still reaches it'() {
    given:
    writeBuild(
      """
        configurations.create('helper')
        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
          extendsFrom configurations.helper
        }
        configurations.helper.defaultDependencies { deps ->
          deps.add(project.dependencies.create('com.google.guava:guava:15.0'))
        }

        dependencyUpdates {
          filterConfigurations { it.name != 'helper' }
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(" - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}" +
      "     contributed by a plugin into the 'helper' configuration")
  }

  def 'Leaves out an entry when the filter rejects the name it shows'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        dependencies {
          implementation 'com.google.inject:guice:3.1'
        }

        ${bucketConfigurations("filterDeclaredConfigurations { it != 'toolBucket' }")}
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains('com.google.guava:guava')
    // The survivor proves the drop is per-entry, not an emptied report.
    result.output.contains('com.google.inject:guice')
  }

  def 'Leaves out an entry declared directly against a resolvable configuration'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.google.guava:guava:15.0'
          implementation 'com.google.inject:guice:3.1'
        }

        dependencyUpdates {
          filterDeclaredConfigurations { it != 'tool' }
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains('com.google.guava:guava')
    result.output.contains('com.google.inject:guice')
  }

  def 'Keeps every entry the build declares itself when the filter rejects everything'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        dependencies {
          implementation 'com.google.inject:guice:3.1'
        }

        dependencyUpdates {
          filterDeclaredConfigurations { false }
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    // Rejecting every configuration silences only attributed entries, never a plain declaration.
    result.output.contains('com.google.inject:guice')
  }

  def 'Keeps an entry when the filter accepts one of the names it shows'() {
    given:
    writeBuild(
      """
        configurations.create('bucketA') {
          canBeResolved = false
          canBeConsumed = false
        }
        configurations.create('bucketB') {
          canBeResolved = false
          canBeConsumed = false
        }
        configurations.create('toolClasspath') {
          canBeResolved = true
          canBeConsumed = false
          extendsFrom configurations.bucketA, configurations.bucketB
        }
        configurations.bucketA.defaultDependencies { deps ->
          deps.add(project.dependencies.create('com.google.guava:guava:15.0'))
        }
        configurations.bucketB.defaultDependencies { deps ->
          deps.add(project.dependencies.create('com.google.guava:guava:15.0'))
        }

        dependencyUpdates {
          filterDeclaredConfigurations { it != 'bucketA' }
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava')
  }

  def 'Leaves out a contributed constraint without touching the declared dependencies'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        dependencies {
          implementation 'com.google.inject:guice:3.1'
        }

        def listener = new org.gradle.api.artifacts.DependencyResolutionListener() {
          void beforeResolve(ResolvableDependencies dependencies) {
            project.dependencies.constraints.add(
              'implementation', 'com.google.guava:guava:15.0')
            gradle.removeListener(this)
          }

          void afterResolve(ResolvableDependencies dependencies) { }
        }
        gradle.addListener(listener)

        dependencyUpdates {
          checkConstraints = true
          filterDeclaredConfigurations { it != 'implementation' }
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains('com.google.guava:guava')
    result.output.contains('com.google.inject:guice')
  }

  def 'Leaves out an unresolved entry when the filter rejects the name it shows'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        dependencies {
          implementation 'com.google.inject:guice:3.1'
        }

        ${bucketConfigurations(
          "filterDeclaredConfigurations { it != 'toolBucket' }",
          'com.github.ben-manes:unresolvable:1.0')}
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=json'])
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    // The drop covers every report section, the unresolved one included.
    jsonReport.unresolved.dependencies.isEmpty()
    jsonReport.current.dependencies*.name == ['guice']
  }

  def 'Keeps an entry a plain declaration also backs'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.google.guava:guava:15.0'
          implementation 'com.google.guava:guava:15.0'
        }

        dependencyUpdates {
          filterDeclaredConfigurations { it != 'tool' }
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    // Rejecting every name the entry shows removes the attribution, never the declared dependency.
    result.output.contains(' - com.google.guava:guava [15.0 -> 16.0-rc1]')
    !result.output.contains("declared in the 'tool' configuration")
  }

  def 'Leaves out a buildscript entry when the filter rejects the name it shows'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          repositories {
            mavenCentral()
          }
          dependencies {
            classpath 'com.google.code.findbugs:jsr305:3.0.1'
          }
        }

        plugins {
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        def listener = new org.gradle.api.artifacts.DependencyResolutionListener() {
          void beforeResolve(ResolvableDependencies dependencies) {
            if (dependencies.name.startsWith('classpath')) {
              project.buildscript.dependencies.add('classpath', 'com.google.inject:guice:3.0')
              gradle.removeListener(this)
            }
          }

          void afterResolve(ResolvableDependencies dependencies) { }
        }
        gradle.addListener(listener)

        dependencyUpdates {
          checkForGradleUpdate = false
          filterDeclaredConfigurations { it != 'classpath' }
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    // A contributed buildscript entry names 'classpath' and is rejected like any other entry.
    !result.output.contains('com.google.inject:guice')
    // The plainly declared classpath dependency is unnamed and stays.
    result.output.contains(' - com.google.code.findbugs:jsr305 [3.0.1 -> ')
  }

  def 'the README recipe compiles and applies under the Kotlin DSL'() {
    given:
    testProjectDir.newFile('build.gradle.kts') <<
      """
        import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

        plugins {
          `java-library`
          id("io.github.ben-manes.versions")
        }

        repositories {
          maven(url = "${mavenRepoUrl}")
        }

        dependencies {
          implementation("com.google.inject:guice:3.1")
        }

        val tool = configurations.create("tool") {
          isCanBeResolved = true
          isCanBeConsumed = false
        }
        tool.defaultDependencies {
          add(project.dependencies.create("com.google.guava:guava:15.0"))
        }

        tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
          checkForGradleUpdate = false
          filterDeclaredConfigurations = Spec<String> { it != "tool" }
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains('com.google.guava:guava')
    result.output.contains('com.google.inject:guice')
  }

  def 'Honors the filter declared in the root project'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        dependencyUpdates {
          checkForGradleUpdate = false
          filterDeclaredConfigurations { it != 'toolBucket' }
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        apply plugin: 'io.github.ben-manes.versions'

        ${bucketConfigurations('checkForGradleUpdate = false')}
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains('com.google.guava:guava')
  }
}
