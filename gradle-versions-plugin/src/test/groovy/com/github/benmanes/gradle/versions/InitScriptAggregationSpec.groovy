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
import spock.lang.Unroll

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
  private static final List<String> RECIPES = ['versionsPlugin', 'settingsEvaluated', 'beforeSettings']
  private static final List<String> SETTINGS_SCRIPT_IDS =
    ['io.github.ben-manes.versions', 'io.github.ben-manes.versions.settings']

  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  @Rule final TemporaryFolder initScriptDir = new TemporaryFolder()
  private String mavenRepoUrl
  private Map<String, File> initScripts
  private File kotlinInitScript

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()

    // Copied rather than read from the classpath that withPluginClasspath injects, so that the two
    // classpaths do not share a classloader, as an init script resolving the plugin for itself does
    // not share one with the build that applies it.
    def classpath = TestKitRunner.create().withPluginClasspath().pluginClasspath
      .findAll { it.exists() }
      .collect { copyOf(it) }
      .collect { "'${it.absolutePath.replace('\\', '/')}'" }
      .join(', ')
    // The init script in the README, and the two earlier ones that builds may still run.
    initScripts = [
      versionsPlugin: """
        apply plugin: com.github.benmanes.gradle.versions.VersionsPlugin
      """,
      settingsEvaluated: """
        settingsEvaluated { settings ->
          if (!settings.pluginManager.hasPlugin('io.github.ben-manes.versions.settings')) {
            settings.pluginManager.apply(com.github.benmanes.gradle.versions.VersionsSettingsPlugin)
          }
        }
      """,
      beforeSettings: """
        beforeSettings { settings ->
          settings.pluginManager.apply(com.github.benmanes.gradle.versions.VersionsSettingsPlugin)
        }
      """,
    ].collectEntries { name, body ->
      def script = initScriptDir.newFile("${name}.init.gradle")
      script <<
        """
          initscript {
            dependencies {
              classpath files(${classpath})
            }
          }
        """.stripIndent() + body.stripIndent()
      [(name): script]
    }

    kotlinInitScript = initScriptDir.newFile('versionsPlugin.init.gradle.kts')
    kotlinInitScript <<
      """
        import com.github.benmanes.gradle.versions.VersionsPlugin

        initscript {
          dependencies {
            classpath(files(${classpath.replace("'", '"')}))
          }
        }

        apply<VersionsPlugin>()
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
    return TestKitRunner.create()
      .withGradleVersion(GradleVersions.CURRENT)
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  @Unroll
  def 'Reports every project when only an init script from #recipe applies the plugin'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'"

    when:
    def result = run('dependencyUpdates', '--init-script', initScripts[recipe].absolutePath)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 2.2 -> 3.1]')
    !result.output.contains('The dependency updates report is missing')

    where:
    recipe << RECIPES
  }

  def 'Reports every project when only a Kotlin init script applies the plugin'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'"

    when:
    def result = run('dependencyUpdates', '--init-script', kotlinInitScript.absolutePath)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 2.2 -> 3.1]')
    !result.output.contains('The dependency updates report is missing')
  }

  @Unroll
  def 'Reports when #pluginId is applied in the settings script and an init script from #recipe runs'() {
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
          id '${pluginId}'
        }

        include 'app'
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '--init-script', initScripts[recipe].absolutePath)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 2.2 -> 3.1]')
    // The copy that defers no longer publishes the settings classpath, so the copy that claimed the
    // build must still see what the settings script declared after its own beforeSettings ran.
    // https://github.com/ben-manes/gradle-versions-plugin/issues/367
    result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
    !result.output.contains('The dependency updates report is missing')

    where:
    [recipe, pluginId] << [RECIPES, SETTINGS_SCRIPT_IDS].combinations()
  }

  def 'Leaves a build to the settings plugin of a release that does not guard against a second copy'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        pluginManagement {
          repositories {
            gradlePluginPortal()
          }
        }

        plugins {
          id 'io.github.ben-manes.versions.settings' version '0.56.0'
        }

        include 'app'
      """.stripIndent()

    when: 'the build resolves its own copy, so the classpath under test reaches the init script only'
    def result = TestKitRunner.create()
      .withGradleVersion(GradleVersions.CURRENT)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '--init-script', initScripts.versionsPlugin.absolutePath)
      .build()

    then: 'the report is in the format of that release, which printed no later version in between'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Unroll
  def 'Reports when a project applies the contributor plugin and an init script from #recipe runs'() {
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
    def result = run('dependencyUpdates', '--init-script', initScripts[recipe].absolutePath)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 2.2 -> 3.1]')
    !result.output.contains('The dependency updates report is missing')

    where:
    recipe << RECIPES
  }

  @Unroll
  def 'Reports under isolated projects when only an init script from #recipe applies the plugin'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'"

    when:
    def result = run('dependencyUpdates', '--init-script', initScripts[recipe].absolutePath,
      '-Dorg.gradle.isolated-projects=true', '--configuration-cache', '--parallel')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('Isolated Projects is an incubating feature.')
    result.output.contains('com.google.inject:guice [2.0 -> 2.2 -> 3.1]')
    !result.output.contains('The dependency updates report is missing')

    where:
    recipe << RECIPES
  }

  @Unroll
  def 'Configures the task by type when #pluginId is applied in the settings script and an init script from #recipe runs'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        plugins {
          id '${pluginId}'
        }

        include 'app'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

        tasks.withType(DependencyUpdatesTask).configureEach {
          rejectVersionIf { it.candidate.version.startsWith('3') }
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '--init-script', initScripts[recipe].absolutePath)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 2.2]')

    where:
    [recipe, pluginId] << [['versionsPlugin', 'settingsEvaluated'], SETTINGS_SCRIPT_IDS].combinations()
  }
}
