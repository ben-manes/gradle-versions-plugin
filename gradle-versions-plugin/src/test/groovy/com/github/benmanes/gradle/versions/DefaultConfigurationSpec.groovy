package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1022')
final class DefaultConfigurationSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
    testProjectDir.newFile('settings.gradle') << "include 'app', 'local'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }
      """.stripIndent()

    // Declares no variant of its own, so that a consumer resolves it through the fallback to its
    // default configuration, as a project that exposes a local aar or jar file does.
    testProjectDir.newFolder('local')
    testProjectDir.newFile('local/local.jar')
    testProjectDir.newFile('local/build.gradle') <<
      """
        configurations.maybeCreate('default')
        artifacts.add('default', file('local.jar'))

        group = 'com.example'
        version = '1.0'
      """.stripIndent()

    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        import org.gradle.api.artifacts.component.ProjectComponentIdentifier

        plugins {
          id 'java-library'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation project(':local')
          // Reported by the aggregate, and kept off the runtime classpath that resolve traverses,
          // as the test repository publishes poms without the files to resolve them to.
          compileOnly 'com.google.inject:guice:2.0'
        }

        // Resolves the graph, which is where the project's variant is selected, and collects the
        // artifact it selects.
        tasks.register('resolve') {
          def classpath = configurations.runtimeClasspath.incoming.artifactView {
            componentFilter { it instanceof ProjectComponentIdentifier }
          }.files
          doLast {
            println "RESOLVED \${classpath.files.collect { it.name }}"
          }
        }
      """.stripIndent()
  }

  private def run(List<String> arguments) {
    return GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  def 'Consumes a project that publishes through its default configuration'() {
    when:
    def result = run([':app:resolve'])

    then:
    result.task(':app:resolve').outcome == SUCCESS
    result.output.contains('RESOLVED [local.jar]')
  }

  def 'Aggregates a project that publishes through its default configuration'() {
    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.task(':local:partialDependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    !result.output.contains('The dependency updates report is missing')
  }
}
