package com.github.benmanes.gradle.versions.version

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Shared
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class SetVersionGroovySpec extends Specification {

  @Rule
  private final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile, propertiesFile
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
    if (propertiesFile) {
      propertiesFile.delete()
    }
  }

  void 'Set version without suffix in build.gradle'() {
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
      .withArguments('setVersion', "--${GetVersion.OPT_NO_SUFFIX}")
      .withPluginClasspath()
      .build()

    then:
    final String buildFileContent = buildFile.getText()
    !result.output.contains('1.0.0-SNAPSHOT')
    !result.output.contains('1.0.0')
    !buildFileContent.contains('1.0.0-SNAPSHOT')
    buildFileContent.contains('1.0.0')
    result.task(':setVersion').outcome == SUCCESS
  }

  void 'Set dev version in build.gradle'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile << """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: "com.github.ben-manes.versions"

        group 'com.github.ben-manes'
        version '1.0.0-SNAPSHOT'
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('setVersion', "-D${GetVersion.OPT_SUFFIX}=dev")
      .withPluginClasspath()
      .build()

    then:
    final String buildFileContent = buildFile.getText()
    !result.output.contains('1.0.0-SNAPSHOT')
    !result.output.contains('1.0.0-dev')
    !buildFileContent.contains('1.0.0-SNAPSHOT')
    buildFileContent.contains('1.0.0-dev')
    result.task(':setVersion').outcome == SUCCESS
  }

  void 'Set current version in build.gradle'() {
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
      .withArguments('setVersion', "-D${GetVersion.OPT_SUFFIX}=SNAPSHOT")
      .withPluginClasspath()
      .build()

    then:
    final String buildFileContent = buildFile.getText()
    !result.output.contains('1.0.0-SNAPSHOT')
    buildFileContent.contains('1.0.0-SNAPSHOT')
    result.output.contains(SetVersion.WARN_DO_NOT_SET_SAME_VERSION)
    result.task(':setVersion').outcome == SUCCESS
  }

  void 'Set rc version in gradle.properties'() {
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
        version = "\${pluginVersion}"
""".stripIndent()
    propertiesFile = testProjectDir.newFile('gradle.properties')
    propertiesFile << '''
        anotherVersion=1.0.0
        pluginVersion=2.0.0-SNAPSHOT
'''.stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('setVersion', "--${GetVersion.OPT_SUFFIX}=rc")
      .withPluginClasspath()
      .build()

    then:
    final String propertiesFileContent = propertiesFile.getText()
    !result.output.contains('2.0.0-SNAPSHOT')
    !result.output.contains('1.0.0')
    !result.output.contains('2.0.0-rc')
    !propertiesFileContent.contains('1.0.0-SNAPSHOT')
    propertiesFileContent.contains('1.0.0')
    propertiesFileContent.contains('2.0.0-rc')
    result.task(':setVersion').outcome == SUCCESS
  }
}
