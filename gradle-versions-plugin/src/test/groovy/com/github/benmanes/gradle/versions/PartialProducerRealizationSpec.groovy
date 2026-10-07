package com.github.benmanes.gradle.versions

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Requires
import spock.lang.Specification

/**
 * Gradle queries a configuration's artifacts while it builds the tooling models that an IDE syncs
 * from, one thread per project and in parallel under `org.gradle.tooling.parallel`. An outgoing
 * artifact that creates its producing task on that query therefore has two threads racing to create
 * the same task, which fails the sync. The race is not deterministic, so what is asserted here is
 * the hazard behind it: resolving the published artifacts must not create the task.
 */
// Gradle 9 requires JVM 17.
@Requires({ jvm.java17Compatible })
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1135')
final class PartialProducerRealizationSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()

  def 'setup'() {
    testProjectDir.newFile('settings.gradle') << "include 'app', 'lib'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        // configureEach runs as a task is realized, so it records what the query below creates.
        def realized = []
        allprojects { p ->
          p.tasks.configureEach { t ->
            if (t.name == 'partialDependencyUpdates') {
              realized << t.path
            }
          }
        }

        gradle.projectsEvaluated {
          // What an IDE does for every project: read the files of every configuration's artifacts.
          def published = []
          allprojects { p ->
            published.addAll(p.configurations.dependencyUpdatesElements.artifacts.files.files)
          }
          // Printed so that a variant that published nothing cannot pass the assertion below by
          // realizing nothing.
          println "PUBLISHED_FILES: " + published.size()
          println "REALIZED_BY_ARTIFACT_QUERY: " + realized
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') << "plugins { id 'java' }"
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') << "plugins { id 'java' }"
  }

  private def run(String... arguments) {
    return TestKitRunner.create()
      .withGradleVersion(GradleVersions.CURRENT)
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  def 'Resolving the published artifacts does not create the producer'() {
    when:
    def result = run('help')

    then:
    // The root publishes its own result and the two it aggregates, each project its own.
    result.output.contains('PUBLISHED_FILES: 5')
    result.output.contains('REALIZED_BY_ARTIFACT_QUERY: []')
  }

  def 'Applying the plugin to an aggregated project does not create the producer'() {
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        subprojects { apply plugin: 'io.github.ben-manes.versions' }
      """.stripIndent()

    when:
    def result = run('help')

    then:
    result.output.contains('PUBLISHED_FILES: 5')
    result.output.contains('REALIZED_BY_ARTIFACT_QUERY: []')
  }

  def 'A producer cannot be pointed away from the file that its project publishes'() {
    given:
    new File(testProjectDir.root, 'app/build.gradle') <<
      """
        tasks.named('partialDependencyUpdates') {
          outputFile = layout.buildDirectory.file('custom.json')
        }
      """.stripIndent()

    when:
    def result = TestKitRunner.create()
      .withGradleVersion(GradleVersions.CURRENT)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .buildAndFail()

    then:
    result.output.contains("property 'outputFile' cannot be changed any further")
  }

  def 'A producer writes the file that its project publishes whatever was set beforehand'() {
    given:
    // An action on the container that precedes the registration runs ahead of the one that the
    // task was registered with.
    new File(testProjectDir.root, 'build.gradle').text =
      "plugins { id 'io.github.ben-manes.versions' apply false }"
    new File(testProjectDir.root, 'app/build.gradle') <<
      """
        tasks.configureEach {
          if (it.name == 'partialDependencyUpdates') {
            it.outputFile = layout.buildDirectory.file('custom.json')
          }
        }
        apply plugin: 'io.github.ben-manes.versions'

        gradle.projectsEvaluated {
          println "PUBLISHED_FILE: " + configurations.dependencyUpdatesElements.artifacts.files.singleFile
        }
      """.stripIndent()

    when:
    def result = run(':app:dependencyUpdates')

    then:
    def published = (result.output =~ /PUBLISHED_FILE: (.*)/)[0][1].trim()
    new File(published).exists()
    !new File(testProjectDir.root, 'app/build/custom.json').exists()
  }
}
