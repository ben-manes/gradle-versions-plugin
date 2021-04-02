package com.github.benmanes.gradle.versions.version

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Shared
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.FAILED
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class GetVersionErrorSpec extends Specification {

  @Rule
  private final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  @Shared
  private String classpathString

  void setupSpec() {
    final URL pluginClasspathResource = getClass().classLoader.getResource('plugin-classpath.txt')
    if (pluginClasspathResource == null) {
      throw new IllegalStateException('Did not find plugin classpath resource, run `testClasses` build task.')
    }

    classpathString = pluginClasspathResource.readLines().collect { final String res -> new File(res) }
      .collect { final File file -> file.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { final String path -> "'$path'" }
      .join(', ')
  }

  void cleanup() {
    if (buildFile) {
      buildFile.delete()
    }
  }

  void 'Warning when version unspecified'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile << """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: "com.github.ben-manes.versions"
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains(GetVersion.WARN_UNSPECIFIED_VERSION)
    result.output.contains('unspecified')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Error when specifying suffix and no-suffix at the same time'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile << """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: "com.github.ben-manes.versions"

        group = 'com.github.ben-manes'
        version = '1.0.0-SNAPSHOT'
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion', "--${GetVersion.OPT_SUFFIX}=rc", "--${GetVersion.OPT_NO_SUFFIX}")
      .withPluginClasspath()
      .buildAndFail()

    then:
    !result.output.contains('1.0.0-SNAPSHOT')
    result.output.contains(GetVersion.ERROR_SUFFIX_AND_NOSUFFIX)
    result.task(':getVersion').outcome == FAILED
  }

  void 'Error when options new-version and no-suffix are given'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile << """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: "com.github.ben-manes.versions"

        group "com.github.ben-manes"
        version "1.0.0-SNAPSHOT"
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('setVersion', "-D${GetVersion.OPT_NEW_VERSION}=1.2.3", "--${GetVersion.OPT_NO_SUFFIX}")
      .withPluginClasspath()
      .buildAndFail()

    then:
    final String buildFileContent = buildFile.getText()
    buildFileContent.contains('1.0.0-SNAPSHOT')
    !result.output.contains('1.0.0-SNAPSHOT')
    result.output.contains(SetVersion.ERROR_NEWVERSION_AND_SUFFIX_OR_NOSUFFIX)
    result.task(':setVersion').outcome == FAILED
  }
}
