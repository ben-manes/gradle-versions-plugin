package com.github.benmanes.gradle.versions.version

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Shared
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class SetVersionMultiModuleSpec extends Specification {

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

  void 'Set versions from 2 modules that define their own version'() {
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
      .withArguments('setVersion', '--increment=minor', '--no-suffix')
      .withPluginClasspath()
      .build()

    then:
    final String module1BuildFileContent = module1BuildFile.getText()
    !module1BuildFileContent.contains('1.0.0-SNAPSHOT')
    module1BuildFileContent.contains('1.1.0')
    final String module2BuildFileContent = module2BuildFile.getText()
    !module2BuildFileContent.contains('2.0.0-SNAPSHOT')
    module2BuildFileContent.contains('2.1.0')
    result.task(':setVersion') == null
    result.task(':module1:setVersion').outcome == SUCCESS
    result.task(':module2:setVersion').outcome == SUCCESS
  }

  void 'Set versions from 2 modules with one which does not define a version'() {
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
      .withArguments('setVersion', '--new-version=v1.2')
      .withPluginClasspath()
      .build()

    then:
    final String module1BuildFileContent = module1BuildFile.getText()
    !module1BuildFileContent.contains('1.0.0-SNAPSHOT')
    module1BuildFileContent.contains('v1.2')
    result.output.contains(SetVersion.WARN_DO_NOT_SET_SAME_VERSION)
    result.task(':setVersion') == null
    result.task(':module1:setVersion').outcome == SUCCESS
    result.task(':module2:setVersion').outcome == SUCCESS
  }

  void 'Set versions from 2 modules with a common version defined in common gradle.properties'() {
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
      .withArguments('setVersion', '-Dsuffix=false', '-Dincrement=major')
      .withPluginClasspath()
      .build()

    then:
    final String propertiesFileContent = propertiesFile.getText()
    !propertiesFileContent.contains('2.0.0-SNAPSHOT')
    propertiesFileContent.contains('3.0.0')
    result.task(':setVersion') == null
    result.task(':module1:setVersion').outcome == SUCCESS
    result.task(':module2:setVersion').outcome == SUCCESS
  }

  void 'Set versions from 2 modules with versions defined in common and local gradle.properties'() {
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
      .withArguments('setVersion', '--increment=technical')
      .withPluginClasspath()
      .build()

    then:
    final String localGradlePropertiesFileContent = localGradlePropertiesFile.getText()
    final String propertiesFileContent = propertiesFile.getText()
    !localGradlePropertiesFileContent.contains('1.0.0-SNAPSHOT')
    localGradlePropertiesFileContent.contains('1.0.1-SNAPSHOT')
    !propertiesFileContent.contains('2.0.0-SNAPSHOT')
    propertiesFileContent.contains('2.0.1-SNAPSHOT')
    result.task(':setVersion') == null
    result.task(':module1:setVersion').outcome == SUCCESS
    result.task(':module2:setVersion').outcome == SUCCESS
  }

  void 'Set versions from 2 modules with a common version defined in a buildSrc plugin'() {
    given:
    module1BuildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        plugins {
            id("common")
        }
""".stripIndent()

    module2BuildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        plugins {
            id("common")
        }
""".stripIndent()

    final File buildSrcFolder = testProjectDir.newFolder('buildSrc')
    final File buildSrcBuildGradle = new File(buildSrcFolder, 'build.gradle.kts')
    buildSrcBuildGradle << '''
        plugins {
            `kotlin-dsl`
        }

        repositories {
            jcenter()
            mavenLocal()
        }

'''.stripIndent()

    final File buildSrcKotlinFolder = new File(buildSrcFolder, 'src/main/kotlin')
    buildSrcKotlinFolder.mkdirs()
    final File buildSrcPlugin = new File(buildSrcKotlinFolder, 'common.gradle.kts')
    buildSrcPlugin << '''
        apply(plugin = "com.github.ben-manes.versions")

        version = "1.2.3-SNAPSHOT"
'''

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('setVersion', '-Dsuffix=false', '-Dincrement=major')
      .withPluginClasspath()
      .build()

    then:
    final String buildSrcPluginContent = buildSrcPlugin.getText()
    !buildSrcPluginContent.contains('1.2.3-SNAPSHOT')
    buildSrcPluginContent.contains('2.0.0')
    result.task(':setVersion') == null
    result.task(':module1:setVersion').outcome == SUCCESS
    result.task(':module2:setVersion').outcome == SUCCESS
  }

  void 'Set versions from 2 modules with one defined in a buildSrc plugin, one from build file through properties'() {
    given:
    module1BuildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        apply(plugin = "com.github.ben-manes.versions")

        version = project.findProperty("pluginVersion").toString()
""".stripIndent()

    propertiesFile = testProjectDir.newFile('gradle.properties')
    propertiesFile << '''
        anotherVersion=4.0.0
        pluginVersion=12.1.8-SNAPSHOT
'''.stripIndent()

    module2BuildFile << """
        buildscript {
          dependencies {
            classpath(files($classpathString))
          }
        }

        plugins {
            id("common")
        }
""".stripIndent()

    final File module2PropertiesFile = new File(module2Folder, 'gradle.properties')
    module2PropertiesFile << '''
        anotherVersion=3.0.0
        pluginVersion=0.0.1-SNAPSHOT
'''.stripIndent()

    final File buildSrcFolder = testProjectDir.newFolder('buildSrc')
    final File buildSrcBuildGradle = new File(buildSrcFolder, 'build.gradle.kts')
    buildSrcBuildGradle << '''
        plugins {
            `kotlin-dsl`
        }

        repositories {
            jcenter()
            mavenLocal()
        }
'''.stripIndent()

    final File buildSrcKotlinFolder = new File(buildSrcFolder, 'src/main/kotlin')
    buildSrcKotlinFolder.mkdirs()
    final File buildSrcPlugin = new File(buildSrcKotlinFolder, 'common.gradle.kts')
    buildSrcPlugin << '''
        apply(plugin = "com.github.ben-manes.versions")
        version = project.findProperty("pluginVersion").toString()
'''

    when:
    final BuildResult result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('setVersion', '-Dsuffix=dev', '-Dincrement=minor')
      .withPluginClasspath()
      .build()

    then:
    final String propertiesFileContent = propertiesFile.getText()
    !propertiesFileContent.contains('12.1.8-SNAPSHOT')
    propertiesFileContent.contains('4.0.0')
    propertiesFileContent.contains('12.2.0-dev')
    final String module2PropertiesFileContent = module2PropertiesFile.getText()
    !module2PropertiesFileContent.contains('0.0.1-SNAPSHOT')
    module2PropertiesFileContent.contains('3.0.0')
    module2PropertiesFileContent.contains('0.1.0-dev')
    result.task(':setVersion') == null
    result.task(':module1:setVersion').outcome == SUCCESS
    result.task(':module2:setVersion').outcome == SUCCESS
  }

}
