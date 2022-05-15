package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

final class ComponentSelectionRuleSourceSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private List<File> pluginClasspath
  private String classpathString
  private String mavenRepoUrl

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.getResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        "Did not find plugin classpath resource, run `testClasses` build task.")
    }

    pluginClasspath = pluginClasspathResource.readLines().collect { new File(it) }
    classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  @Unroll
  def 'component selection works with rule-source (#assignment)'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent

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
          implementation 'com.google.inject:guice:2.0'
        }

        dependencyUpdates.resolutionStrategy ${assignment} {
          componentSelection {
            all(new Rule())
          }
        }

        class Rule {

          @Mutate
          void select(ComponentSelectionWithCurrent selection) {
            if (selection.candidate.version == "3.1" && selection.currentVersion == "2.0") {
              selection.reject("Guice 3.1 not allowed")
            }
          }
        }
        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    assignment << [' ', '=']
  }
}
