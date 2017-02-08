package com.github.benmanes.gradle.versions;

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder

import spock.lang.Specification
import spock.lang.Unroll

public class DifferentGradleVersionsSpec extends Specification {

  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  File buildFile

  List<File> pluginClasspath

  def setup() {
    buildFile = testProjectDir.newFile('build.gradle')


    def pluginClasspathResource = getClass().classLoader.findResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException("Did not find plugin classpath resource, run `testClasses` build task.")
    }

    pluginClasspath = pluginClasspathResource.readLines().collect { new File(it) }
  }


  @Unroll
  def "dependencyUpdates task completes without errors with Gradle #gradleVersion"() {
    given:
    def classpathString = pluginClasspath
        .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
        .collect { "'$it'" }
        .join(", ")
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    def srdErrWriter = new StringWriter()

    buildFile << """
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


        """

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
    gradleVersion << ['1.5', '1.12', '2.0', '3.3']
  }
}
