package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

final class KotlinDslUsageSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile

  def 'setup'() {
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()

    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile <<
      """
        import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

        plugins {
          java
          id("com.github.ben-manes.versions")
        }

        apply(plugin = "com.github.ben-manes.versions")

        repositories {
          maven(url = "${mavenRepoUrl}")
        }

        dependencies {
          implementation("com.google.inject:guice:2.0")
        }
        """.stripIndent()
  }

  @Unroll
  def "user friendly kotlin-dsl with"() {
    given:
    buildFile << '''
      tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
          checkForGradleUpdate = true
          outputFormatter = "json"
          outputDir = "build/dependencyUpdates"
          reportfileName = "report"
          resolutionStrategy {
            componentSelection {
              all {
                if (candidate.version == "3.1" && currentVersion != "") {
                  reject("Guice 3.1 not allowed")
                }
              }
            }
          }
        }
    '''

    when:
    def result = GradleRunner.create()
      .withPluginClasspath()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .build()

    then:
    result.output.contains('''com.google.inject:guice [2.0 -> 3.0]''')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Unroll
  def "user friendly kotlin-dsl with #outputFormatter produces #expectedOutput"() {
    given:
    buildFile << """
      tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
          checkForGradleUpdate = true
          $outputFormatter
          outputDir = "build/dependencyUpdates"
          reportfileName = "report"
          resolutionStrategy {
            componentSelection {
              all {
                if (candidate.version == "3.1" && currentVersion != "") {
                  reject("Guice 3.1 not allowed")
                }
              }
            }
          }
        }
    """

    when:
    def result = GradleRunner.create()
      .withPluginClasspath()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .build()

    then:
    result.output.contains(expectedOutput)
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    outputFormatter << [
      'outputFormatter = "json"',
      'outputFormatter { print("Custom report") }'
    ]
    expectedOutput << [
      'com.google.inject:guice [2.0 -> 3.0]',
      'Custom report'
    ]
  }
}
