package com.github.benmanes.gradle.versions.version

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Shared
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class GetVersionMultiModuleSpec extends Specification {

  @Rule
  private final TemporaryFolder testProjectDir = new TemporaryFolder()
  @Shared
  private File settingsGradle
  @Shared
  private File module1Folder
  @Shared
  private File module2Folder
  @Shared
  private File module1BuildFile
  @Shared
  private File module2BuildFile
  @Shared
  private File propertiesFile
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

  void setup() {
    if (!settingsGradle || !settingsGradle.exists()) {
      settingsGradle = testProjectDir.newFile('settings.gradle.kts')
      settingsGradle << '''
rootProject.name = "dummy"
include("module1", "module2")
'''
    }
    if (!module1Folder || !module1Folder.exists()) {
      module1Folder = testProjectDir.newFolder('module1')
    }
    if (!module1BuildFile || !module1BuildFile.exists()) {
      module1BuildFile = new File(module1Folder, 'build.gradle.kts')
    }
    if (!module2Folder || !module2Folder.exists()) {
      module2Folder = testProjectDir.newFolder('module2')
    }
    if (!module2BuildFile || !module2BuildFile.exists()) {
      module2BuildFile = new File(module2Folder, 'build.gradle.kts')
    }
  }

  void cleanup() {
    if (module1BuildFile) {
      module1BuildFile.delete()
    }
    if (module2BuildFile) {
      module2BuildFile.delete()
    }
    if (propertiesFile) {
      propertiesFile.delete()
    }
  }

  void 'Get versions from 2 modules that define their own version'() {
    given:
    module1BuildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        apply(plugin = "com.github.ben-manes.versions")

        group = "com.github.ben-manes"
        version = "1.0.0-SNAPSHOT"
""".stripIndent()

    module2BuildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        apply(plugin = "com.github.ben-manes.versions")

        group = "com.github.ben-manes"
        version = "2.0.0-SNAPSHOT"
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('1.0.0-SNAPSHOT')
    result.output.contains('2.0.0-SNAPSHOT')
    result.task(':getVersion') == null
    result.task(':module1:getVersion').outcome == SUCCESS
    result.task(':module2:getVersion').outcome == SUCCESS
  }

  void 'Get versions from 2 modules with one which does not define a version'() {
    given:
    module1BuildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        apply(plugin = "com.github.ben-manes.versions")

        group = "com.github.ben-manes"
        version = "1.0.0-SNAPSHOT"
""".stripIndent()

    module2BuildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        apply(plugin = "com.github.ben-manes.versions")

        group = "com.github.ben-manes"
""".stripIndent()

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('getVersion')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('1.0.0-SNAPSHOT')
    result.output.contains('unspecified')
    result.task(':getVersion') == null
    result.task(':module1:getVersion').outcome == SUCCESS
    result.task(':module2:getVersion').outcome == SUCCESS
  }

  void 'Get versions from 2 modules with a common version defined in common gradle.properties'() {
    given:
    module1BuildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        apply(plugin = "com.github.ben-manes.versions")

        group = "com.github.ben-manes"
        version = project.findProperty("pluginVersion").toString()
""".stripIndent()

    module2BuildFile << """
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
    result.output.contains('2.0.0-SNAPSHOT')
    !result.output.contains('unspecified')
    result.task(':getVersion') == null
    result.task(':module1:getVersion').outcome == SUCCESS
    result.task(':module2:getVersion').outcome == SUCCESS
  }

  void 'Get versions from 2 modules with versions defined in common and local gradle.properties'() {
    given:
    module1BuildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        apply(plugin = "com.github.ben-manes.versions")

        group = "com.github.ben-manes"
        version = project.findProperty("pluginVersion").toString()
""".stripIndent()

    final File localGradlePropertiesFile = new File(module1Folder, 'gradle.properties')
    localGradlePropertiesFile << '''pluginVersion=1.0.0-SNAPSHOT'''.stripIndent()

    module2BuildFile << """
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
    result.output.contains('1.0.0-SNAPSHOT')
    result.output.contains('2.0.0-SNAPSHOT')
    !result.output.contains('unspecified')
    result.task(':getVersion') == null
    result.task(':module1:getVersion').outcome == SUCCESS
    result.task(':module2:getVersion').outcome == SUCCESS
  }

}
