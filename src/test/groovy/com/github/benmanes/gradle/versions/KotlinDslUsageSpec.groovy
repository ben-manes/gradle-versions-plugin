package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

final class KotlinDslUsageSpec extends Specification {

  @Rule TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile

  def 'setup'() {
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()

    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile <<
      """
        plugins {
          java
          id("com.github.ben-manes.versions")
        }
        
        apply(plugin = "com.github.ben-manes.versions")

        repositories {
          maven(url = "${mavenRepoUrl}")
        }

        dependencies {
          compile("com.google.inject:guice:2.0")
        }
        """.stripIndent()
  }

  @Unroll
  def "user friendly kotlin-dsl"() {
    given:
    def srdErrWriter = new StringWriter()
    buildFile << '''
      tasks {
        "dependencyUpdates"(com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask::class) {
          resolutionStrategy {
            componentSelection {
              all {
                if (candidate.version == "3.0") {
                  reject("Guava 3.0 not allowed")
                }
              }
            }
          }
        }
      }
    '''

    when:
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withPluginClasspath()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .forwardStdError(srdErrWriter)
      .build()

    then:
    result.output.contains('''Failed to determine the latest version for the following dependencies (use --info for details):
       | - com.google.inject:guice'''.stripMargin())
    srdErrWriter.toString().empty

    where:
    gradleVersion << ['4.8']
  }
}
