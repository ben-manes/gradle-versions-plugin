package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

/**
 * With {@code rejectPreReleaseVersions}, on by default, a pre-release candidate is left out of the
 * report unless the current version is itself a pre-release, in which case a newer pre-release is
 * still reported. A convention the built-in markers do not cover goes in a {@code rejectVersionIf}
 * filter, which is applied in addition to the built-in check.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/440')
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

  private Map runReport(List<String> extraArguments = []) {
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(['dependencyUpdates'] + extraArguments)
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

  def 'with rejectPreReleaseVersions false the pre-release candidate is reported'() {
    given:
    writeBuildFile('com.example:prerelease-widget:1.0', 'rejectPreReleaseVersions = false')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies[0].available.milestone == '1.2-beta'
  }

  def 'the command line option turns the filter off for one run'() {
    given:
    writeBuildFile('com.example:prerelease-widget:1.0')

    when:
    def report = runReport(['--no-reject-pre-release-versions'])

    then:
    report.outdated.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies[0].available.milestone == '1.2-beta'
  }

  def 'the command line option overrides the property set to false in the build'() {
    given:
    writeBuildFile('com.example:prerelease-widget:1.0', 'rejectPreReleaseVersions = false')

    when:
    def report = runReport(['--reject-pre-release-versions'])

    then:
    report.current.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies.isEmpty()
  }

  def 'a module with only pre-releases published and no declared version to fall back to is unresolved'() {
    given: 'only 1.0-alpha and 1.0-beta of peer are published, and the declared 0.9 never was'
    writeBuildFile('com.example:prerelease-peer:0.9')

    when:
    def report = runReport()

    then: 'every candidate is rejected, so the dynamic query matches nothing'
    // A known cost of filtering by rejection, the same as with a rejectVersionIf that rejects
    // everything. The build still succeeds, and the rejected versions are listed in the reason.
    report.unresolved.dependencies*.name == ['prerelease-peer']
    report.unresolved.dependencies[0].reason.contains('1.0-beta')
    report.outdated.dependencies.isEmpty()
  }

  def 'a newer pre-release is still reported to a build already on one'() {
    given:
    writeBuildFile('com.example:prerelease-peer:1.0-alpha')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-peer']
    report.outdated.dependencies[0].available.milestone == '1.0-beta'
  }

  def 'a rejectVersionIf filter covers a convention the built-in markers do not'() {
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
    given: 'the filter rejects guava 16.0, leaving only the 16.0-rc1 the built-in check rejects'
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

  def 'under the integration revision a snapshot is reported'() {
    given:
    writeBuildFile('com.example:snapshot-mixed:1.5', "revision = 'integration'")

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['snapshot-mixed']
    report.outdated.dependencies[0].available.integration == '2.0-SNAPSHOT'
  }

  def 'under the integration revision the filter is off, and the property reads false'() {
    given: 'a beta rather than a snapshot, so the exemption covers the whole revision'
    writeBuildFile('com.example:prerelease-widget:1.0', '''
          revision = 'integration'
          doLast { println "rejectPreReleaseVersions=$rejectPreReleaseVersions" }
        ''')

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text) as Map

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('rejectPreReleaseVersions=false')
    report.outdated.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies[0].available.integration == '1.2-beta'
  }

  def 'under the integration revision an explicit setting still applies'() {
    given:
    writeBuildFile('com.example:prerelease-widget:1.0', '''
          revision = 'integration'
          rejectPreReleaseVersions = true
        ''')

    when:
    def report = runReport()

    then:
    report.current.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies.isEmpty()
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

  def 'a subproject inherits rejectPreReleaseVersions from the root task'() {
    given:
    writeMultiProjectBuild('com.example:prerelease-widget:1.0', 'rejectPreReleaseVersions = false')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies[0].available.milestone == '1.2-beta'
  }

  def 'a qualifier not in the built-in markers is passed through'() {
    given:
    writeBuildFile('com.example:prerelease-flagged:1.0')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-flagged']
    report.outdated.dependencies[0].available.milestone == '2.0-flagged'
  }
}
