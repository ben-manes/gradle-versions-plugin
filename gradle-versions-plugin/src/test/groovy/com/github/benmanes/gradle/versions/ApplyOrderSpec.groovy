package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.IgnoreIf
import spock.lang.Issue
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Unroll

/**
 * A specification for applying the plugin to a project where a configuration has already resolved.
 * A convention plugin is applied alongside others whose order its consumer chooses, so a plugin
 * that resolves one of its own configurations while it applies may run first.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1049')
final class ApplyOrderSpec extends Specification {
  private static final String GRADLE_8 = '8.4'
  private static final String GRADLE_9 = GradleVersions.CURRENT
  private static final List<String> PLUGINS =
    ['io.github.ben-manes.versions', 'io.github.ben-manes.versions.contributor']

  /**
   * How each Gradle version refuses a default action, for the configuration that resolved and for
   * the one that resolution only observed. Gradle 9 reworded both. Pinning them fails the spec if a
   * case stops refusing, rather than passing on coverage it no longer has.
   */
  private static final Map<String, Map<String, String>> REFUSALS = [
    (GRADLE_8): [
      resolved: "Cannot change dependencies of dependency configuration ':probe' after it has" +
        ' been resolved.',
      observed: "Cannot change dependencies of dependency configuration ':base' after it has been" +
        ' included in dependency resolution.',
    ],
    (GRADLE_9): [
      resolved: "Cannot mutate the state of configuration ':probe' after the configuration was" +
        ' resolved.',
      observed: "Cannot mutate the state of configuration ':base' after the configuration's child" +
        " configuration ':probe' was resolved.",
    ],
  ]

  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  /** Applies the plugin after a resolution that both resolves 'probe' and observes 'base'. */
  private static String applyAfterResolution(String plugin) {
    """
      plugins {
        id '$plugin' apply false
      }

      // Stands in for a plugin that resolves one of its own configurations while it applies.
      // Resolving 'probe' also observes 'base', which refuses a default action while still
      // reporting itself as unresolved.
      configurations.create('base')
      configurations.create('probe') { extendsFrom configurations.base }
      configurations.probe.resolve()

      apply plugin: '$plugin'
    """.stripIndent()
  }

  /** A 'tool' configuration whose guava dependency only {@code defaultDependencies} contributes. */
  private static String toolConfiguration() {
    """
      configurations.create('tool') {
        canBeResolved = true
        canBeConsumed = false
      }
      configurations.tool.defaultDependencies { deps ->
        deps.add(project.dependencies.create('com.google.guava:guava:15.0'))
      }
    """.stripIndent()
  }

  private def run(String gradleVersion, String... arguments) {
    return GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  // Gradle 9 requires JVM 17.
  @IgnoreIf({ data.gradleVersion.startsWith('9') && !jvm.java17Compatible })
  @Unroll
  def 'Applies #plugin on Gradle #gradleVersion to a project that already resolved a configuration'() {
    given:
    testProjectDir.newFile('build.gradle') << applyAfterResolution(plugin)

    when:
    def result = run(gradleVersion, 'help', '--info')

    then:
    result.task(':help').outcome == SUCCESS

    // A root project's path is ':', so its qualified configuration names read '::probe'.
    and: 'the skipped mark is logged rather than dropped in silence'
    result.output.contains('Skipping the plugin mark for configuration ::probe')
    result.output.contains('Skipping the plugin mark for configuration ::base')

    and: 'this Gradle still refuses both, in the wordings the guard has to catch'
    result.output.contains(REFUSALS[gradleVersion].resolved)
    result.output.contains(REFUSALS[gradleVersion].observed)

    where:
    [plugin, gradleVersion] << [PLUGINS, [GRADLE_8, GRADLE_9]].combinations()
  }

  def 'Marks a contributed dependency alongside a configuration that already resolved'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        ${applyAfterResolution('io.github.ben-manes.versions')}

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        ${toolConfiguration()}

        dependencyUpdates {
          checkForGradleUpdate = false
        }
      """.stripIndent()

    when:
    def result = run(GRADLE_8, 'dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains("contributed by a plugin into the 'tool' configuration")
  }

  def 'Reports what a plugin contributed to a configuration that already resolved as declared'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions' apply false
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        ${toolConfiguration()}

        // Resolves the graph alone, as the test repository publishes poms without jars.
        configurations.tool.incoming.resolutionResult.root

        apply plugin: 'io.github.ben-manes.versions'

        dependencyUpdates {
          checkForGradleUpdate = false
        }
      """.stripIndent()

    when:
    def result = run(GRADLE_8, 'dependencyUpdates', '--info')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('Skipping the plugin mark for configuration ::tool')

    and: 'the attribution the mark carries is what giving it up costs'
    result.output.contains(' - com.google.guava:guava [15.0 -> 16.0]')
    !result.output.contains('contributed by a plugin')
  }

  // Only under isolated projects does a subproject's own plugin register its producer, as the root
  // otherwise registers one for every project before their scripts have resolved anything, and the
  // second registration returns early. Gradle 9 requires JVM 17.
  @Requires({ jvm.java17Compatible })
  def 'Marks a dependency the contributor plugin adds after its project already resolved'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencyUpdates {
          checkForGradleUpdate = false
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        ${applyAfterResolution('io.github.ben-manes.versions.contributor')}

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        ${toolConfiguration()}
      """.stripIndent()

    when:
    def result = run(GRADLE_9, 'dependencyUpdates', '-Dorg.gradle.isolated-projects=true',
      '--configuration-cache')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains("contributed by a plugin into the 'tool' configuration")
  }
}
