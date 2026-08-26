package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for surfacing configurations that were skipped because applying the build's
 * resolutionStrategy to them threw.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/801')
final class SkippedConfigurationSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  private def run(List<String> arguments) {
    return GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  // The measured #801 trigger: a withModule id missing its ':name' half.
  private static String throwingStrategy() {
    return '''
      dependencyUpdates.resolutionStrategy {
        componentSelection { rules ->
          rules.withModule('com.google.guava') { }
        }
      }
      '''.stripIndent()
  }

  def 'A resolution strategy that throws surfaces every skipped configuration'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven { url '${mavenRepoUrl}' }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }

        dependencyUpdates {
          checkForGradleUpdate = false
        }

        ${throwingStrategy()}
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=plain,json,xml,html', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('No dependencies found.')
    result.output.contains('Failed to inspect the dependencies of the following configurations')
    result.output.contains("'compileClasspath'")
    result.output.contains('Skipping configuration')

    def json = new JsonSlurper().parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    json.skipped.count > 0
    def entry = json.skipped.configurations.find { it.name == 'compileClasspath' }
    entry != null
    entry.project == ':'
    entry.reason.contains('Could not add a component selection rule')
    entry.reason.contains('Cannot convert the provided notation')

    def report = new File(testProjectDir.root, 'build/dependencyUpdates/report.txt').text
    report.contains("'compileClasspath' in root project")

    def xml = new File(testProjectDir.root, 'build/dependencyUpdates/report.xml').text
    xml.contains('<skipped>')
    xml.contains('<skippedConfiguration>')
    xml.contains('<project>:</project>')
    xml.contains('<name>compileClasspath</name>')

    def html = new File(testProjectDir.root, 'build/dependencyUpdates/report.html').text
    html.contains('Skipped configurations')
  }

  // A resolution failure's message is an exception toString() chain, arbitrary text that Gradle
  // itself routinely fills with generics like "List<String>", so the HTML report must escape it
  // rather than interpolate it raw into a <td>.
  def 'The HTML report escapes a skipped configuration reason'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven { url '${mavenRepoUrl}' }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }

        dependencyUpdates {
          checkForGradleUpdate = false
        }

        dependencyUpdates.resolutionStrategy {
          throw new IllegalStateException(
            'cannot convert to List<String> & <b>bold</b> <script>alert(1)</script>')
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=html', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS

    def html = new File(testProjectDir.root, 'build/dependencyUpdates/report.html').text
    !html.contains('<script>alert(1)</script>')
    !html.contains('List<String>')
    !html.contains('<b>bold</b>')
    html.contains('&lt;script&gt;alert(1)&lt;/script&gt;')
    html.contains('List&lt;String&gt;')
    html.contains('&lt;b&gt;bold&lt;/b&gt;')
  }

  def 'A project whose configurations are skipped is annotated beside the surviving entries'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app', 'lib'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencyUpdates {
          checkForGradleUpdate = false
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        apply plugin: 'java'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven { url '${mavenRepoUrl}' }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }

        ${throwingStrategy()}
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        apply plugin: 'java'

        repositories {
          maven { url '${mavenRepoUrl}' }
        }

        dependencies {
          implementation 'com.google.inject:guice:3.0'
        }
      """.stripIndent()

    when:
    def result = run([':dependencyUpdates', '-DoutputFormatter=plain,json', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS

    def json = new JsonSlurper().parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    def guice = json.outdated.dependencies.find { it.name == 'guice' }
    guice != null
    guice.version == '3.0'

    json.skipped.configurations.every { it.project == ':app' }
    json.skipped.count > 0

    result.output.contains('guice [3.0 ->')
    result.output.contains('Failed to inspect the dependencies of the following configurations')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/801')
  def 'Two configurations skipped for the same reason both survive into the report'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        buildscript {
          repositories {
            maven { url '${mavenRepoUrl}' }
          }

          dependencies {
            classpath 'com.example.settings-demo:com.example.settings-demo.gradle.plugin:1.0'
          }
        }

        plugins {
          id 'io.github.ben-manes.versions.settings'
        }
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          repositories {
            maven { url '${mavenRepoUrl}' }
          }

          dependencies {
            classpath 'com.example.settings-demo:com.example.settings-demo.gradle.plugin:1.0'
          }
        }

        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencyUpdates {
          checkForGradleUpdate = false
        }

        ${throwingStrategy()}
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=plain,json', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS

    def json = new JsonSlurper().parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    def classpathEntries = json.skipped.configurations.findAll { it.name == 'classpath' && it.project == ':' }
    classpathEntries.size() == 2
    json.skipped.count == 2

    // The root project's own path renders as "root project", not the "::classpath" of a naive
    // "$project.path:$configuration.name" concatenation.
    !result.output.contains('::classpath')
    result.output.contains('root project')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/801')
  def 'A project inside a composite build whose configurations are skipped is annotated beside the surviving entries'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        includeBuild('child') {
          dependencySubstitution {
            substitute module('com.example:swapped') using project(':')
          }
        }
        include 'app', 'lib'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencyUpdates {
          checkForGradleUpdate = false
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        apply plugin: 'java-library'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven { url '${mavenRepoUrl}' }
        }

        dependencies {
          api 'com.example:swapped:1.0'
          api 'com.google.inject:guice:2.0'
        }

        ${throwingStrategy()}
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        apply plugin: 'java-library'

        repositories {
          maven { url '${mavenRepoUrl}' }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        plugins {
          id 'java-library'
        }

        group = 'com.example'
        version = '1.0'
      """.stripIndent()

    when:
    def result = run([':dependencyUpdates', '-DoutputFormatter=plain,json', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')

    def json = new JsonSlurper().parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    json.skipped.count == 6
    json.skipped.configurations.every { it.project == ':app' }
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/801')
  def 'Configurations skipped for distinct reasons are printed with a heading and a warning each'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven { url '${mavenRepoUrl}' }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }

        dependencyUpdates {
          checkForGradleUpdate = false
        }

        def attempts = new java.util.concurrent.atomic.AtomicInteger()
        dependencyUpdates.resolutionStrategy {
          throw new IllegalStateException('boom ' + attempts.incrementAndGet() + '\\nswallowed detail')
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=plain,json', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS

    def json = new JsonSlurper().parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    json.skipped.count > 1
    json.skipped.configurations*.reason.unique().size() == json.skipped.count
    json.skipped.configurations*.name == json.skipped.configurations*.name.toSorted()

    def report = new File(testProjectDir.root, 'build/dependencyUpdates/report.txt').text
    def section = report.substring(report.indexOf('Failed to inspect the dependencies'))
    section.readLines().count { it.startsWith(' - ') } == json.skipped.count
    !report.contains('swallowed detail')

    result.output.readLines().count { it.contains('Skipping configuration') } == json.skipped.count
    !result.output.contains('swallowed detail')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/801')
  def 'The HTML report keeps a project beside the configuration it skipped'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app', 'lib'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencyUpdates {
          checkForGradleUpdate = false
        }

        subprojects {
          apply plugin: 'java'
          apply plugin: 'io.github.ben-manes.versions'

          repositories {
            maven { url '${mavenRepoUrl}' }
          }

          dependencies {
            implementation 'com.google.inject:guice:2.0'
          }

          dependencyUpdates.resolutionStrategy {
            throw new IllegalStateException('boom')
          }
        }
      """.stripIndent()

    when:
    def result = run([':dependencyUpdates', '-DoutputFormatter=html', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS

    def html = new File(testProjectDir.root, 'build/dependencyUpdates/report.html').text
    def rows = (html.substring(html.indexOf('<h2>Skipped configurations</h2>')) =~
      /<tr><td>(.*?)<\/td><td>(.*?)<\/td><td>(.*?)<\/td><\/tr>/)
      .collect { [it[1], it[2]] }
      .findAll { !it[0].startsWith('<b>') }

    rows.every { it[0].split('<br>').length == it[1].split('<br>').length }
    rows.any { it[0].split('<br>').length > 2 }
    rows.any { it[0].contains(':app') && it[0].contains(':lib') }
  }
}
