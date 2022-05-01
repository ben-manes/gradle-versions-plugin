package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

final class ConstraintsSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  def "Show updates for an api dependency constraint"() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'com.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def "Does not override explicit dependency with constraint"() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'com.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:3.0'
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('com.google.inject:guice [3.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def "Does not show updates for an api dependency constraint when disabled"() {
    given:
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'com.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('No dependencies found.')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Unroll
  def 'Do not show updates for an gradle constraint (added in 7.3.2/6.9.2) with Gradle #gradleVersion'() {
    given:
    ExpandoMetaClass.disableGlobally()
    def srdErrWriter = new StringWriter()
    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile <<
      """
        plugins {
            java
            id("com.github.ben-manes.versions")
        }

        tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
            checkConstraints = true
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .forwardStdError(srdErrWriter)
      .withPluginClasspath()
      .build()

    then:
    !result.output.contains('org.apache.logging.log4j:log4j-core [2.16.0 -> ')
    srdErrWriter.toString().empty
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    gradleVersion << [
      '6.9.2',
      '7.3.2',
      '7.3.3'
    ]
  }

  def "Show updates for log4j-core even if the constraint added by gradle is ignored"() {
    given:
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    ExpandoMetaClass.disableGlobally()
    def srdErrWriter = new StringWriter()
    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile <<
      """
        plugins {
            java
            id("com.github.ben-manes.versions")
        }
        repositories {
            maven {
                url = uri("${mavenRepoUrl}")
          }
        }

        dependencies {
          constraints {
            implementation ("org.apache.logging.log4j:log4j-core:2.16.0")
          }
        }

        tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
            checkConstraints = true
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withGradleVersion('7.3.2')
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .forwardStdError(srdErrWriter)
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('org.apache.logging.log4j:log4j-core [2.16.0 -> ')
    srdErrWriter.toString().empty
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def "Show updates for a dependencies constraint in init scripts"() {
    given:
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    ExpandoMetaClass.disableGlobally()
    def srdErrWriter = new StringWriter()
    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile <<
      """
        plugins {
            java
            id("com.github.ben-manes.versions")
        }
        repositories {
            maven {
                url = uri("${mavenRepoUrl}")
          }
        }
        tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
            checkBuildEnvironmentConstraints = true
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withGradleVersion('7.3.2')
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .forwardStdError(srdErrWriter)
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('org.apache.logging.log4j:log4j-core [2.16.0 -> ')
    srdErrWriter.toString().empty
    result.task(':dependencyUpdates').outcome == SUCCESS
  }
}
