package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

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

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          compile 'com.google.inject:guice:2.0'
        }
        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .forwardStdError(srdErrWriter)
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    srdErrWriter.toString().empty

    where:
    gradleVersion << [
      '2.0',
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
    ]
  }
}
