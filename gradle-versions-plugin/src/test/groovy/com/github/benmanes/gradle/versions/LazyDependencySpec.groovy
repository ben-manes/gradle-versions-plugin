package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A configuration's declared dependency set is read before {@code withDependencies} and
 * {@code defaultDependencies} actions contribute to it, so a lazily-added dependency is
 * discarded as undeclared, or reported with its resolved version as its declared one.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/987
 */
final class LazyDependencySpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private List<File> pluginClasspath
  private String reportFolder
  private String classpathString
  private String mavenRepoUrl

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.getResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        "Did not find plugin classpath resource, run `testClasses` build task.")
    }

    pluginClasspath = pluginClasspathResource.readLines().collect { new File(it) }
    classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
    reportFolder = "${testProjectDir.root.path.replaceAll("\\\\", '/')}/build/dependencyUpdates"
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/987')
  def 'dependency added via withDependencies on a bucket configuration is not discarded'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.implementation.withDependencies { deps ->
          deps.add(project.dependencies.create('com.google.guava:guava:15.0'))
        }

        def isNonStable = { String version ->
          def stableKeyword = ['RELEASE', 'FINAL', 'GA'].any { it -> version.toUpperCase().contains(it) }
          def regex = /^[0-9,.v-]+(-r)?\$/
          return !stableKeyword && !(version ==~ regex)
        }

        tasks.named('dependencyUpdates').configure {
          outputFormatter = 'json'
          checkForGradleUpdate = false
          rejectVersionIf {
            isNonStable(it.candidate.version) && !isNonStable(it.currentVersion)
          }
        }
        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    report.count == 1
    report.current.dependencies*.name == ['guava']
    report.current.dependencies[0].version == '15.0'
    report.outdated.dependencies.isEmpty()
    report.undeclared.dependencies.isEmpty()
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/987')
  def 'dependency added via defaultDependencies on an undeclared configuration is not discarded'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }
        configurations.tool.defaultDependencies { deps ->
          deps.add(project.dependencies.create('com.google.guava:guava:15.0'))
        }

        tasks.named('dependencyUpdates').configure {
          outputFormatter = 'json'
          checkForGradleUpdate = false
        }
        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    report.count == 1
    report.outdated.dependencies*.name == ['guava']
    report.outdated.dependencies[0].version == '15.0'
    report.outdated.dependencies[0].available.milestone == '16.0-rc1'
    report.current.dependencies.isEmpty()
    report.undeclared.dependencies.isEmpty()
  }
}
