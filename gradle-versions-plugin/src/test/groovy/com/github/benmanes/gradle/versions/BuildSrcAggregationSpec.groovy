package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

final class BuildSrcAggregationSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String classpathString
  private String mavenRepoUrl

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.getResource('plugin-classpath.txt')
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        'Did not find plugin classpath resource, run `testClasses` build task.')
    }
    classpathString = pluginClasspathResource.readLines()
      .collect { it.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(', ')
    mavenRepoUrl = getClass().getResource('/maven/').toURI()

    testProjectDir.newFile('settings.gradle') << "rootProject.name = 'outer'"
    testProjectDir.newFile('build.gradle') <<
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

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('buildSrc')
    testProjectDir.newFile('buildSrc/build.gradle') <<
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

        dependencies {
          testImplementation 'com.google.guava:guava:15.0'
          testImplementation 'com.google.inject:guice:2.0'
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

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1075')
  def 'Names a buildSrc build by its build tree path rather than the root project path'() {
    when:
    def result = run(':buildSrc:dependencyUpdates')

    then:
    // GradleRunner.build() throws unless every requested task succeeds, so reaching here already
    // proves the task ran; TestKit does not surface a buildSrc task's outcome through result.task().
    result.output.contains(':buildSrc Project Dependency Updates')
    !result.output.contains('\n: Project Dependency Updates')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1075')
  def 'Names the projects of a buildSrc build with subprojects by their build tree paths'() {
    given:
    testProjectDir.newFile('buildSrc/settings.gradle') << "include 'logic'"
    testProjectDir.newFolder('buildSrc', 'logic')
    testProjectDir.newFile('buildSrc/logic/build.gradle') <<
      """
        apply plugin: 'java'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          testImplementation 'com.google.inject:guice:2.2'
        }
      """.stripIndent()

    when:
    def result = run(':buildSrc:dependencyUpdates')

    then:
    // The root's label is a prefix of the subproject's, so it is matched to the end of its line.
    result.output.contains('declared in :buildSrc\n')
    result.output.contains('declared in :buildSrc:logic\n')
    !result.output.contains('declared in root project')
    !result.output.contains('The dependency updates report is missing')
  }

  def 'Excludes buildSrc from the outer build aggregated report'() {
    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    !result.output.contains('com.google.guava:guava')
  }
}
