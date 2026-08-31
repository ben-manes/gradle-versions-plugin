package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Gradle flattens a version-catalog plugin alias to a bare required range on the buildscript
 * classpath marker, dropping {@code strictly}/{@code prefer}/{@code reject}, and leaves a marker
 * indistinguishable from one the plugins block versioned inline. These specs pin the reading that
 * bounds such a marker by the range it carries, whichever form declared it.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/755')
final class PluginMarkerCatalogSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  private void catalog(String toml) {
    testProjectDir.newFolder('gradle')
    testProjectDir.newFile('gradle/libs.versions.toml') << toml.stripIndent()
  }

  private void pluginManagementSettings(String resolutionStrategyBody = '') {
    testProjectDir.newFile('settings.gradle') <<
      """
        pluginManagement {
          repositories {
            maven {
              url = '${mavenRepoUrl}'
            }
          }
          $resolutionStrategyBody
        }
      """.stripIndent()
  }

  private void buildFile(String pluginsExtra, String taskBody) {
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
          $pluginsExtra
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
          $taskBody
        }
      """.stripIndent()
  }

  private def runOn(String gradleVersion, List<String> extraArgs = []) {
    def runner = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(['dependencyUpdates'] + extraArgs)
      .withPluginClasspath()
    if (gradleVersion != 'current') {
      runner = runner.withGradleVersion(gradleVersion)
    }
    return runner.build()
  }

  @Unroll
  def 'a catalog plugin alias with a strictly range bounds the marker on Gradle #gradleVersion'() {
    given:
    pluginManagementSettings()
    catalog(
      """
        [versions]
        demo = { strictly = "[1.0, 2[", prefer = "1.0" }

        [plugins]
        demo = { id = "com.example.settings-demo", version.ref = "demo" }
      """)
    buildFile(
      'alias(libs.plugins.demo) apply false',
      """
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    def result = runOn(gradleVersion)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
    result.output.contains(
      ' - com.example.settings-demo:com.example.settings-demo.gradle.plugin:1.0')

    where:
    gradleVersion << ['current', '8.4']
  }

  @Unroll
  def 'a range written inline in the plugins block bounds the marker #catalogState'() {
    given:
    pluginManagementSettings()
    if (withCatalog) {
      catalog(
        """
          [versions]
          demo = { strictly = "[1.0, 2[", prefer = "1.0" }

          [plugins]
          demo = { id = "com.example.settings-demo", version.ref = "demo" }
        """)
    }
    buildFile(
      "id 'com.example.settings-demo' version '[1.0, 2[' apply false",
      """
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    def result = runOn('current')

    then: 'an unused alias for the same id is not what the report follows from'
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
    result.output.contains(
      ' - com.example.settings-demo:com.example.settings-demo.gradle.plugin:1.0')

    where:
    catalogState                | withCatalog
    'with a catalog present'    | true
    'with no catalog at all'    | false
  }

  def 'a plus-shorthand version bounds the marker'() {
    given:
    pluginManagementSettings()
    buildFile(
      "id 'com.example.settings-demo' version '1.+' apply false",
      """
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    def result = runOn('current')

    then: 'the shorthand states an interval as much as a bracketed range does'
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
    result.output.contains(
      ' - com.example.settings-demo:com.example.settings-demo.gradle.plugin:1.0')
  }

  def "the rule reads the marker's flattened constraint"() {
    given:
    pluginManagementSettings()
    catalog(
      """
        [versions]
        demo = { strictly = "[1.0, 2[", prefer = "1.0" }

        [plugins]
        demo = { id = "com.example.settings-demo", version.ref = "demo" }
      """)
    buildFile(
      'alias(libs.plugins.demo) apply false',
      """
        rejectVersionIf {
          if (candidate.module == 'com.example.settings-demo.gradle.plugin') {
            println "PROBE strict='\${versionConstraint?.strictVersion}'" +
              " prefer='\${versionConstraint?.preferredVersion}'" +
              " required='\${versionConstraint?.requiredVersion}'"
          }
          return false
        }
      """)

    when:
    def result = runOn('current')

    then: 'a rule is handed what the build declared, never a bound rebuilt on its behalf'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      "PROBE strict='' prefer='' required='[1.0, 2['")
  }

  def 'a version rewritten by eachPlugin replaces the declared range'() {
    given:
    pluginManagementSettings(
      """
        resolutionStrategy {
          eachPlugin {
            if (requested.id.id == 'com.example.settings-demo') {
              useVersion('2.0')
            }
          }
        }
      """)
    catalog(
      """
        [versions]
        demo = { strictly = "[1.0, 2[", prefer = "1.0" }

        [plugins]
        demo = { id = "com.example.settings-demo", version.ref = "demo" }
      """)
    buildFile(
      'alias(libs.plugins.demo) apply false',
      """
        rejectVersionIf {
          if (candidate.module == 'com.example.settings-demo.gradle.plugin') {
            println "PROBE strict='\${versionConstraint?.strictVersion}'" +
              " required='\${versionConstraint?.requiredVersion}'"
          }
          return false
        }
      """)

    when:
    def result = runOn('current')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains("PROBE strict='' required='2.0'")
    result.output.contains(
      ' - com.example.settings-demo:com.example.settings-demo.gradle.plugin:2.0')
  }

  def 'differing catalog aliases for one id do not change the marker bound'() {
    given:
    pluginManagementSettings()
    catalog(
      """
        [versions]
        demoAlpha = { strictly = "[1.0, 2[", prefer = "1.0" }
        demoBeta = { strictly = "[1.0, 2[", prefer = "1.1" }

        [plugins]
        demoAlpha = { id = "com.example.settings-demo", version.ref = "demoAlpha" }
        demoBeta = { id = "com.example.settings-demo", version.ref = "demoBeta" }
      """)
    buildFile(
      'alias(libs.plugins.demoAlpha) apply false',
      """
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    def result = runOn('current')

    then: 'the range on the marker is what applies, so a second alias for the id is beside the point'
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
    result.output.contains(
      ' - com.example.settings-demo:com.example.settings-demo.gradle.plugin:1.0')
  }

  def 'a project dependency stating a range is not bounded by it'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url = '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.example.settings-demo:com.example.settings-demo.gradle.plugin:[1.0, 2['
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
          rejectVersionIf {
            !satisfiesDeclaredBound
          }
        }
      """.stripIndent()

    when:
    def result = runOn('current')

    then: 'a transitive can push an ordinary module past its range without failing the build'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
  }

  @Unroll
  def 'an exact version from #declaration does not bound the marker'() {
    given:
    pluginManagementSettings()
    catalog(
      """
        [plugins]
        demo = { id = "com.example.settings-demo", version = { require = "1.0", reject = ["2.0"] } }
      """)
    buildFile(
      pluginsLine,
      """
        rejectVersionIf {
          if (candidate.module == 'com.example.settings-demo.gradle.plugin') {
            println "PROBE rejects=\${versionConstraint?.rejectedVersions}"
          }
          !satisfiesDeclaredBound
        }
      """)

    when:
    def result = runOn('current')

    then: 'an exact required version is a floor resolution may rise above, as it is elsewhere'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('PROBE rejects=[]')
    result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')

    where:
    declaration         | pluginsLine
    'the plugins block' | "id 'com.example.settings-demo' version '1.0' apply false"
    'the catalog alias' | 'alias(libs.plugins.demo) apply false'
  }
}
