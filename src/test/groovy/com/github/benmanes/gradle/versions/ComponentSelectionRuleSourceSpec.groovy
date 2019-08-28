package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

final class ComponentSelectionRuleSourceSpec extends Specification {

  @Rule TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private List<File> pluginClasspath

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.findResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        "Did not find plugin classpath resource, run `testClasses` build task.")
    }

    pluginClasspath = pluginClasspathResource.readLines().collect { new File(it) }
  }

  @Unroll
  def 'component selection works with rule-source'() {
    given:
    def classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    def srdErrWriter = new StringWriter()

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
          compile 'com.google.inject:guice:2.0'
        }
        
        dependencyUpdates.resolutionStrategy {
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
      .forwardStdError(srdErrWriter)
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    srdErrWriter.toString().empty
  }
}
