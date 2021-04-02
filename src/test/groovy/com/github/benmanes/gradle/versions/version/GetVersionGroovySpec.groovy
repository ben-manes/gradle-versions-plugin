package com.github.benmanes.gradle.versions.version

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Shared
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class GetVersionGroovySpec extends Specification {

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

  void 'Get version from build.gradle'() {
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
      .withArguments('getVersion')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('1.0.0-SNAPSHOT')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Get version from build.gradle with beta suffix'() {
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
      .withArguments('getVersion', "-D${GetVersion.OPT_SUFFIX}=beta")
      .withPluginClasspath()
      .build()

    then:
    !result.output.contains('1.0.0-SNAPSHOT')
    result.output.contains('1.0.0-beta')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Get version from build.gradle without suffix and existing prefix'() {
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
        version "rel12-1.0.0-SNAPSHOT"
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion', "--${GetVersion.OPT_NO_SUFFIX}")
      .withPluginClasspath()
      .build()

    then:
    !result.output.contains('1.0.0-SNAPSHOT')
    result.output.contains('1.0.0')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Get version from build.gradle with default suffix'() {
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
        version "1.0.0"
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion', "-D${GetVersion.OPT_SUFFIX}=true")
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('1.0.0-SNAPSHOT')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Get version from build.gradle with 3 digits version, technical increment and existing suffix'() {
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
        version '1.0.0-beta'
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion', "--${GetVersion.OPT_INCREMENT}=technical")
      .withPluginClasspath()
      .build()

    then:
    !result.output.contains('1.0.0-beta')
    result.output.contains('1.0.1-beta')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Get version from build.gradle with 3 digits version, minor increment, default suffix and existing prefix'() {
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
        version "v1.0.1"
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion', "-D${GetVersion.OPT_INCREMENT}=minor", "--${GetVersion.OPT_SUFFIX}=true")
      .withPluginClasspath()
      .build()

    then:
    !result.output.contains('v1.0.1-dev')
    result.output.contains('v1.1.0-SNAPSHOT')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Get version from build.gradle with 3 digits version, major increment and rc suffix'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile << """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: "com.github.ben-manes.versions"

        group = "com.github.ben-manes"
        version = "1.2.3-dev"
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion', "--${GetVersion.OPT_SUFFIX}=rc", "--${GetVersion.OPT_INCREMENT}=major")
      .withPluginClasspath()
      .build()

    then:
    !result.output.contains('1.2.3-dev')
    result.output.contains('2.0.0-rc')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Get version from build.gradle with 4 digits version, 4th digit increment, existing suffix and prefix'() {
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
        version "feature/42-1.2.3.4dev"
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion', "-D${GetVersion.OPT_INCREMENT}=4")
      .withPluginClasspath()
      .build()

    then:
    !result.output.contains('feature/42-1.2.3.4dev')
    result.output.contains('feature/42-1.2.3.5dev')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Get version from build.gradle with 2 digits version, minor increment, existing prefix'() {
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
        version "v1.0"
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion', "--${GetVersion.OPT_INCREMENT}=minor")
      .withPluginClasspath()
      .build()

    then:
    !result.output.contains('v1.0')
    result.output.contains('v1.1')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Get version from build.gradle with custom default suffix configured in build file'() {
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
        version "1.0.0"

        versions {
          ${GetVersion.OPT_DEFAULT_SUFFIX} = 'alpha'
        }
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion', "-D${GetVersion.OPT_SUFFIX}=true")
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('1.0.0-alpha')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Get version from gradle.properties'() {
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
        version "\${pluginVersion}"
""".stripIndent()
    propertiesFile = testProjectDir.newFile('gradle.properties')
    propertiesFile << '''
        anotherVersion=1.0.0
        pluginVersion=2.0.0-SNAPSHOT
'''.stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion')
      .withPluginClasspath()
      .build()

    then:
    !result.output.contains('1.0.0')
    result.output.contains('2.0.0-SNAPSHOT')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Get version from gradle.properties with already existing suffix'() {
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
        version "\${pluginVersion}"
""".stripIndent()
    propertiesFile = testProjectDir.newFile('gradle.properties')
    propertiesFile << '''
        anotherVersion: '1.0.0'
        pluginVersion: '2.0.0-SNAPSHOT'
'''.stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion', "--${GetVersion.OPT_SUFFIX}=SNAPSHOT")
      .withPluginClasspath()
      .build()

    then:
    !result.output.contains('1.0.0')
    result.output.contains('2.0.0-SNAPSHOT')
    result.task(':getVersion').outcome == SUCCESS
  }
}
