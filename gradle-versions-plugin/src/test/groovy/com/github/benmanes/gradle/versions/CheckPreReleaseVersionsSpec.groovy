package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

/**
 * {@code rejectPreReleaseVersions} withholds a pre-release candidate from the report by default,
 * unless the build already declares a pre-release, in which case a newer pre-release is still
 * offered. A convention the built-in markers do not cover is named in a {@code rejectVersionIf}
 * filter, which composes with this one rather than replacing it.
 */
final class CheckPreReleaseVersionsSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String reportFolder
  private String classpathString
  private String mavenRepoUrl

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.getResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        "Did not find plugin classpath resource, run `testClasses` build task.")
    }

    def pluginClasspath = pluginClasspathResource.readLines().collect { new File(it) }
    classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
    reportFolder = "${testProjectDir.root.path.replaceAll("\\\\", '/')}/build/dependencyUpdates"
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  private File writeBuildFile(String dependency, String taskConfig = '') {
    def file = testProjectDir.newFile('build.gradle')
    file <<
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
          implementation '$dependency'
        }

        tasks.named('dependencyUpdates').configure {
          outputFormatter = 'json'
          checkForGradleUpdate = false
          $taskConfig
        }
        """.stripIndent()
    return file
  }

  private Map runReport() {
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    assert result.task(':dependencyUpdates').outcome == SUCCESS
    return new JsonSlurper().parseText(new File(reportFolder, 'report.json').text) as Map
  }

  def 'a pre-release candidate is hidden by default'() {
    given:
    writeBuildFile('com.example:prerelease-widget:1.0')

    when:
    def report = runReport()

    then:
    report.current.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies.isEmpty()
  }

  def 'rejectPreReleaseVersions false restores the pre-release candidate'() {
    given:
    writeBuildFile('com.example:prerelease-widget:1.0', 'rejectPreReleaseVersions = false')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies[0].available.milestone == '1.2-beta'
  }

  def 'a build already on a pre-release is still offered a newer pre-release'() {
    given:
    writeBuildFile('com.example:prerelease-peer:1.0-alpha')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-peer']
    report.outdated.dependencies[0].available.milestone == '1.0-beta'
  }

  def 'a rejectVersionIf filter names a convention the built-in markers do not cover'() {
    given:
    writeBuildFile('com.example:prerelease-flagged:1.0', '''
          rejectVersionIf {
            candidate.version.endsWith('-flagged') && !currentVersion.endsWith('-flagged')
          }
        ''')

    when:
    def report = runReport()

    then:
    report.current.dependencies*.name == ['prerelease-flagged']
    report.outdated.dependencies.isEmpty()
  }

  def 'the built-in filter and a rejectVersionIf filter both apply'() {
    given: 'the filter rejects guava 16.0, leaving only the 16.0-rc1 the built-in check withholds'
    writeBuildFile('com.google.guava:guava:15.0', '''
          rejectVersionIf {
            candidate.version == '16.0'
          }
        ''')

    when:
    def report = runReport()

    then: 'neither candidate survives, so the two compose rather than one replacing the other'
    report.current.dependencies*.name == ['guava']
    report.outdated.dependencies.isEmpty()
  }

  def 'the integration revision is left alone, since every snapshot is a pre-release'() {
    given:
    writeBuildFile('com.example:snapshot-mixed:1.5', "revision = 'integration'")

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['snapshot-mixed']
    report.outdated.dependencies[0].available.integration == '2.0-SNAPSHOT'
  }

  private void writeMultiProjectBuild(String dependency, String taskConfig) {
    testProjectDir.newFile('settings.gradle') << "include 'app'"
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        subprojects {
          apply plugin: 'java'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        tasks.named('dependencyUpdates').configure {
          outputFormatter = 'json'
          checkForGradleUpdate = false
          $taskConfig
        }
        """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        dependencies {
          implementation '$dependency'
        }
        """.stripIndent()
  }

  def 'a subproject takes rejectPreReleaseVersions from the root task'() {
    given:
    writeMultiProjectBuild('com.example:prerelease-widget:1.0', 'rejectPreReleaseVersions = false')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies[0].available.milestone == '1.2-beta'
  }

  def 'a marker the built-in set does not recognize is passed through, not withheld'() {
    given:
    writeBuildFile('com.example:prerelease-flagged:1.0')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-flagged']
    report.outdated.dependencies[0].available.milestone == '2.0-flagged'
  }
}
