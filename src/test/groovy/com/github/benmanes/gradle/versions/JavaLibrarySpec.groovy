package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner

final class JavaLibrarySpec extends BaseSpecification {
  def "Show updates for an api dependency in a java-library project"() {
    given:
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    def srdErrWriter = new StringWriter()

    buildFile <<
      """
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
        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withGradleVersion('4.6')
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
