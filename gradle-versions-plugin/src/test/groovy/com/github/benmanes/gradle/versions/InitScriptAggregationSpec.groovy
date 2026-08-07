package com.github.benmanes.gradle.versions

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Requires
import spock.lang.Specification

/**
 * An init script injects the plugin into every build, so it reaches builds that apply the plugin
 * themselves. The init script's classpath is a separate classloader from the build's, which gives
 * the same class two identities, and Gradle keys the tasks, configurations, and shared services
 * that the plugin registers by name.
 */
// Gradle 9 requires JVM 17.
@Requires({ jvm.java17Compatible })
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1023')
final class InitScriptAggregationSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  @Rule final TemporaryFolder initScriptDir = new TemporaryFolder()
  private String mavenRepoUrl
  private File initScript

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()

    // Copied rather than read from the classpath that withPluginClasspath injects, so that the two
    // classpaths do not share a classloader, as an init script resolving the plugin for itself does
    // not share one with the build that applies it.
    def classpath = GradleRunner.create().withPluginClasspath().pluginClasspath
      .findAll { it.exists() }
      .collect { copyOf(it) }
      .collect { "'${it.absolutePath.replace('\\', '/')}'" }
      .join(', ')
    initScript = initScriptDir.newFile('versions.init.gradle')
    initScript <<
      """
        initscript {
          dependencies {
            classpath files(${classpath})
          }
        }

        beforeSettings { settings ->
          settings.pluginManager.apply(com.github.benmanes.gradle.versions.VersionsSettingsPlugin)
        }
      """.stripIndent()

    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        plugins {
          id 'java'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
  }

  /** Returns a copy of the classpath entry, which may be a jar or a directory of classes. */
  private File copyOf(File entry) {
    def target = new File(initScriptDir.newFolder(), entry.name)
    Path source = entry.toPath()
    if (entry.directory) {
      Files.walk(source).withCloseable { paths ->
        paths.forEach { path ->
          def destination = target.toPath().resolve(source.relativize(path))
          if (Files.isDirectory(path)) {
            Files.createDirectories(destination)
          } else {
            Files.createDirectories(destination.parent)
            Files.copy(path, destination, REPLACE_EXISTING)
          }
        }
      }
    } else {
      Files.copy(source, target.toPath(), REPLACE_EXISTING)
    }
    return target
  }

  private def run(String... arguments) {
    return GradleRunner.create()
      .withGradleVersion('9.7.0')
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  def 'Reports every project when only the init script applies the plugin'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'"

    when:
    def result = run('dependencyUpdates', '--init-script', initScript.absolutePath)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    !result.output.contains('The dependency updates report is missing')
  }

  def 'Reports when the settings script applies the plugin the init script already applied'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        buildscript {
          repositories {
            maven {
              url = '${mavenRepoUrl}'
            }
          }

          dependencies {
            classpath 'com.example.settings-demo:com.example.settings-demo.gradle.plugin:1.0'
          }
        }

        plugins {
          id 'io.github.ben-manes.versions.settings'
        }

        include 'app'
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '--init-script', initScript.absolutePath)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    // The copy that defers no longer publishes the settings classpath, so the copy that claimed the
    // build must still see what the settings script declared after its own beforeSettings ran.
    // https://github.com/ben-manes/gradle-versions-plugin/issues/367
    result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
    !result.output.contains('The dependency updates report is missing')
  }

  def 'Reports when a project applies the contributor plugin the init script already applied'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'"
    new File(testProjectDir.root, 'app/build.gradle').text =
      """
        plugins {
          id 'java'
          id 'io.github.ben-manes.versions.contributor'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '--init-script', initScript.absolutePath)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    !result.output.contains('The dependency updates report is missing')
  }

  def 'Reports under isolated projects when only the init script applies the plugin'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'"

    when:
    def result = run('dependencyUpdates', '--init-script', initScript.absolutePath,
      '-Dorg.gradle.isolated-projects=true', '--configuration-cache', '--parallel')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('Isolated Projects is an incubating feature.')
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    !result.output.contains('The dependency updates report is missing')
  }
}
