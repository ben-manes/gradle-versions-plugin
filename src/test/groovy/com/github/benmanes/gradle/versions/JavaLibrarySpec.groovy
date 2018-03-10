package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

public class JavaLibrarySpec extends Specification {

  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  File buildFile

  List<File> pluginClasspath

  def setup() {
    buildFile = testProjectDir.newFile('build.gradle')

    def pluginClasspathResource = getClass().classLoader.findResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        "Did not find plugin classpath resource, run `testClasses` build task.")
    }

    pluginClasspath = pluginClasspathResource.readLines().collect { new File(it) }
  }

  def "Show updates for an api dependency in a java-library project"() {
    given:
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    def srdErrWriter = new StringWriter()

    buildFile << """
        plugins {
          id 'com.github.ben-manes.versions'
        }
        apply plugin: 'java-library'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }


        """

    when:
    def result = GradleRunner.create()
      .withGradleVersion('3.4-rc-1')
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath(pluginClasspath)
      .forwardStdError(srdErrWriter)
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    srdErrWriter.toString().empty
  }
}
