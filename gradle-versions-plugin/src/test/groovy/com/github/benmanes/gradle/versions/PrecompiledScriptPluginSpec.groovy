package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.IgnoreIf
import spock.lang.Issue
import spock.lang.Specification

final class PrecompiledScriptPluginSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()

  // Later releases read the plugin's metadata at any language version, so only the oldest supported
  // release can catch a regression. On JDK 21 and later its `kotlin-dsl` fails with "Unknown Kotlin
  // JVM target: 21".
  @IgnoreIf({ jvm.java21Compatible })
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1108')
  def 'A precompiled script plugin compiles against the task class on Gradle 8.4'() {
    given:
    // A build depending on the published plugin compiles against its jar alone, since the plugin's
    // dependencies are runtime only, so the Kotlin libraries it was built with stay off the compile
    // classpath here too.
    def classpath = GradleRunner.create().withPluginClasspath().pluginClasspath
    def (jars, directories) = classpath.split { it.name.endsWith('.jar') }
    testProjectDir.newFile('settings.gradle.kts') << 'rootProject.name = "consumer"'
    testProjectDir.newFile('build.gradle.kts') << 'plugins { id("versions-convention") }'
    testProjectDir.newFolder('buildSrc', 'src', 'main', 'kotlin')
    testProjectDir.newFile('buildSrc/build.gradle.kts') <<
      """
        plugins {
          `kotlin-dsl`
        }

        repositories {
          gradlePluginPortal()
        }

        dependencies {
          implementation(files(${quoted(directories)}))
          runtimeOnly(files(${quoted(jars)}))
        }
      """.stripIndent()
    testProjectDir.newFile('buildSrc/src/main/kotlin/versions-convention.gradle.kts') <<
      """
        import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

        plugins {
          id("io.github.ben-manes.versions")
        }

        tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
          checkForGradleUpdate = false
        }
      """.stripIndent()

    when:
    def result = TestKitRunner.create()
      .withGradleVersion('8.4')
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .build()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  private static String quoted(List<File> files) {
    return files.collect { file ->
      def escaped = file.absolutePath.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$')
      return '"' + escaped + '"'
    }.join(', ')
  }
}
