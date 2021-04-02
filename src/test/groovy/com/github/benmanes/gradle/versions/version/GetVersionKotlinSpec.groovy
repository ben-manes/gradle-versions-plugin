package com.github.benmanes.gradle.versions.version

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Shared
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class GetVersionKotlinSpec extends Specification {

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

    classpathString = pluginClasspathResource.readLines().collect { String res -> new File(res) }
      .collect { final File file -> file.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { final String path -> "\"$path\"" }
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

  void 'Get version from build.gradle.kts'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        apply(plugin = "com.github.ben-manes.versions")

        group = "com.github.ben-manes"
        version = "1.0.0-SNAPSHOT"
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

  void 'Get version from build.gradle.kts with beta suffix'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        apply(plugin = "com.github.ben-manes.versions")

        group = "com.github.ben-manes"
        version = "1.0.0-SNAPSHOT"
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion', "--${GetVersion.OPT_SUFFIX}=beta")
      .withPluginClasspath()
      .build()

    then:
    !result.output.contains('1.0.0-SNAPSHOT')
    result.output.contains('1.0.0-beta')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Get version from build.gradle.kts without suffix'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        apply(plugin = "com.github.ben-manes.versions")

        group = "com.github.ben-manes"
        version = "1.0.0-SNAPSHOT"
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion', "-D${GetVersion.OPT_NO_SUFFIX}=true")
      .withPluginClasspath()
      .build()

    then:
    !result.output.contains('1.0.0-SNAPSHOT')
    result.output.contains('1.0.0')
    result.task(':getVersion').outcome == SUCCESS
  }

  void 'Get version from gradle.properties'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        apply(plugin = "com.github.ben-manes.versions")

        group = "com.github.ben-manes"
        version = project.findProperty("pluginVersion").toString()
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
    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile << """
        fun properties(key: String) = project.findProperty(key).toString()

        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        apply(plugin = "com.github.ben-manes.versions")

        group = "com.github.ben-manes"
        version = properties("pluginVersion")
""".stripIndent()
    propertiesFile = testProjectDir.newFile('gradle.properties')
    propertiesFile << '''
        anotherVersion: '1.0.0'
        pluginVersion: '2.0.0-SNAPSHOT'
'''.stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion', "-D${GetVersion.OPT_SUFFIX}=SNAPSHOT")
      .withPluginClasspath()
      .build()

    then:
    !result.output.contains('1.0.0')
    result.output.contains('2.0.0-SNAPSHOT')
    result.task(':getVersion').outcome == SUCCESS
  }
}
