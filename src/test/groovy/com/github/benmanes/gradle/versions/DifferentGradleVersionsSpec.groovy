package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.*

final class DifferentGradleVersionsSpec extends Specification {

  @Rule TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private List<File> pluginClasspath

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.findResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        "Did not find plugin classpath resource, run `testClasses` build task.")
    }

    pluginClasspath = pluginClasspathResource.readLines().collect { new File(it) }
  }

  @Unroll
  def 'dependencyUpdates task completes without errors with Gradle #gradleVersion'() {
    given:
    def classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    def srdErrWriter = new StringWriter()

    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: "com.github.ben-manes.versions"

        // Gradle 7.0+ do not allow directly using compile configuration so we monkey patch
        // an implementation configuration in for older Gradle versions.
        if (configurations.findByName('implementation') == null) {
          configurations.create('implementation') {
            extendsFrom configurations.compile
          }
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }

        dependencyUpdates.resolutionStrategy {
          componentSelection {
            all {
              if (candidate.version == "3.1" && currentVersion == "2.0") {
                reject("Guice 3.1 not allowed")
              }
            }
          }
        }
        """.stripIndent()

    when:
    def arguments = ['dependencyUpdates']
    // Warning mode reporting only supported on recent versions.
    if (gradleVersion.substring(0, gradleVersion.indexOf('.')).toInteger() >= 6) {
      arguments.add('--warning-mode=fail')
    }
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .forwardStdError(srdErrWriter)
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    srdErrWriter.toString().empty

    where:
    gradleVersion << [
      '3.3',
      '3.4',
      '3.5',
      '4.0',
      '4.1',
      '4.2',
      '4.3',
      '4.4',
      '4.5',
      '4.6',
      '4.7',
      '4.8',
      '4.9',
      '4.10',
      '5.0',
      '5.1.1',
      '5.2.1',
      '5.3.1',
      '5.4.1',
      '5.5.1',
      '5.6',
      '6.0'
    ]
  }

  @Unroll
  def 'dependencyUpdates task uses specified release channel with Gradle #gradleReleaseChannel'() {
    given:
    def classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    def srdErrWriter = new StringWriter()

    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: "com.github.ben-manes.versions"

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          compile 'com.google.inject:guice:2.0'
        }

        dependencyUpdates.gradleReleaseChannel="${gradleReleaseChannel}"

        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withGradleVersion('3.3')
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .forwardStdError(srdErrWriter)
      .build()

    then:
    result.output.contains("Gradle ${gradleReleaseChannel} updates:")
    !result.output.contains("UP-TO-DATE")
    srdErrWriter.toString().empty

    where:
    gradleReleaseChannel << [
      CURRENT.id,
      RELEASE_CANDIDATE.id,
      NIGHTLY.id
    ]
  }
}
