package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

final class KotlinDslUsageSpec extends BaseSpecification {

  @Rule TemporaryFolder testProjectDir = new TemporaryFolder()
  File buildFile
  List<File> pluginClasspath

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.findResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        "Did not find plugin classpath resource, run `testClasses` build task.")
    }

    pluginClasspath = pluginClasspathResource.readLines().collect { new File(it) }

    def classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "\"$it\"" }
      .join(", ")
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()

    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        plugins {
          java
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
