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
 * classpath marker, dropping {@code strictly}/{@code prefer}/{@code reject}. These specs pin the
 * recovery that rebuilds the marker's constraint from the catalog it came from.
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

  def 'the rule reads the catalog declaration verbatim'() {
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

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      "PROBE strict='[1.0, 2[' prefer='1.0' required='[1.0, 2['")
  }

  def 'a version rewritten by eachPlugin recovers nothing'() {
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

  def 'two aliases for one id with differing constraints recover nothing'() {
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
          if (candidate.module == 'com.example.settings-demo.gradle.plugin') {
            println "PROBE strict='\${versionConstraint?.strictVersion}'"
          }
          !satisfiesDeclaredBound
        }
      """)

    when:
    def result = runOn('current', ['--info'])

    then: 'no alias is credited, so nothing bounds the report and 2.0 is offered'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains("PROBE strict=''")
    result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
    result.output.contains(
      'Multiple version catalog aliases for com.example.settings-demo:com.example.settings-demo.gradle.plugin ' +
        'state differing version constraints; keeping the declared constraint')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/755')
  def 'a plain alias for one id blocks recovery from a rich one'() {
    given:
    pluginManagementSettings()
    catalog(
      """
        [plugins]
        demoPlain = { id = "com.example.settings-demo", version = "1.0" }
        demoBounded = { id = "com.example.settings-demo", version = { require = "1.0", reject = ["2.0"] } }
      """)
    buildFile(
      'alias(libs.plugins.demoPlain) apply false',
      """
        rejectVersionIf {
          if (candidate.module == 'com.example.settings-demo.gradle.plugin') {
            println "PROBE rejects=\${versionConstraint?.rejectedVersions}"
          }
          !satisfiesDeclaredBound
        }
      """)

    when:
    def result = runOn('current', ['--info'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('PROBE rejects=[]')
    result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
    result.output.contains(
      'Multiple version catalog aliases for com.example.settings-demo:com.example.settings-demo.gradle.plugin ' +
        'state differing version constraints; keeping the declared constraint')
  }

  def 'two aliases for one id with identical constraints still recover'() {
    given:
    pluginManagementSettings()
    catalog(
      """
        [versions]
        demo = { strictly = "[1.0, 2[", prefer = "1.0" }

        [plugins]
        demoAlpha = { id = "com.example.settings-demo", version.ref = "demo" }
        demoBeta = { id = "com.example.settings-demo", version.ref = "demo" }
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

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
    result.output.contains(
      ' - com.example.settings-demo:com.example.settings-demo.gradle.plugin:1.0')
  }

  def 'a project dependency on the marker coordinate recovers nothing from the catalog'() {
    given:
    catalog(
      """
        [versions]
        demo = { strictly = "[1.0, 2[", prefer = "1.0" }

        [plugins]
        demo = { id = "com.example.settings-demo", version.ref = "demo" }
      """)
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

    then: 'the alias states a strictly the declaration never did, so 2.0 stays on offer'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
  }

  def 'a project dependency at a plain version recovers nothing'() {
    given:
    catalog(
      """
        [plugins]
        demo = { id = "com.example.settings-demo", version = { require = "1.0", reject = ["2.0"] } }
      """)
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
          implementation 'com.example.settings-demo:com.example.settings-demo.gradle.plugin:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
          rejectVersionIf {
            if (candidate.module == 'com.example.settings-demo.gradle.plugin') {
              println "PROBE rejects=\${versionConstraint?.rejectedVersions}"
            }
            !satisfiesDeclaredBound
          }
        }
      """.stripIndent()

    when:
    def result = runOn('current')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('PROBE rejects=[]')
    result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
  }

  def 'a project dependency stating the alias range recovers nothing'() {
    given:
    catalog(
      """
        [versions]
        demo = { strictly = "[1.0, 2[", prefer = "1.0" }

        [plugins]
        demo = { id = "com.example.settings-demo", version.ref = "demo" }
      """)
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
            if (candidate.module == 'com.example.settings-demo.gradle.plugin') {
              println "PROBE strict='\${versionConstraint?.strictVersion}'" +
                " prefer='\${versionConstraint?.preferredVersion}'"
            }
            !satisfiesDeclaredBound
          }
        }
      """.stripIndent()

    when:
    def result = runOn('current')

    then: 'nothing is credited from the alias, so the strictly it states never applies'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains("PROBE strict='' prefer=''")
    result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')
  }

  def 'a versionless declaration recovers nothing from a reject-only alias'() {
    given:
    catalog(
      """
        [plugins]
        demo = { id = "com.example.settings-demo", version = { reject = ["2.0"] } }
      """)
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
          constraints {
            implementation 'com.example.settings-demo:com.example.settings-demo.gradle.plugin:1.0'
          }
          implementation 'com.example.settings-demo:com.example.settings-demo.gradle.plugin'
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
          rejectVersionIf {
            if (candidate.module == 'com.example.settings-demo.gradle.plugin') {
              println "PROBE rejects=\${versionConstraint?.rejectedVersions}"
            }
            return false
          }
        }
      """.stripIndent()

    when:
    def result = runOn('current')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('PROBE rejects=[]')
  }

  def 'a declaration with its own rich constraint keeps it'() {
    given:
    catalog(
      """
        [versions]
        demo = { strictly = "[1.0, 2[", reject = ["1.0"] }

        [plugins]
        demo = { id = "com.example.settings-demo", version.ref = "demo" }
      """)
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
          implementation('com.example.settings-demo:com.example.settings-demo.gradle.plugin') {
            version {
              strictly '[1.0, 2['
            }
          }
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
          rejectVersionIf {
            if (candidate.module == 'com.example.settings-demo.gradle.plugin') {
              println "PROBE rejects=\${versionConstraint?.rejectedVersions}"
            }
            return false
          }
        }
      """.stripIndent()

    when:
    def result = runOn('current')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('PROBE rejects=[]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/755')
  @Unroll
  def 'an exact version from #declaration recovers nothing'() {
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

    then: 'the flattened exact version could as well be stated inline, so no bound is credited'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('PROBE rejects=[]')
    result.output.contains(
      'com.example.settings-demo:com.example.settings-demo.gradle.plugin [1.0 -> 2.0]')

    where:
    declaration         | pluginsLine
    'the plugins block' | "id 'com.example.settings-demo' version '1.0' apply false"
    'the catalog alias' | 'alias(libs.plugins.demo) apply false'
  }

  def 'a plain string plugin version recovers nothing'() {
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
      "id 'com.example.settings-demo' version '1.0' apply false",
      """
        rejectVersionIf {
          if (candidate.module == 'com.example.settings-demo.gradle.plugin') {
            println "PROBE strict='\${versionConstraint?.strictVersion}'"
          }
          return false
        }
      """)

    when:
    def result = runOn('current')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains("PROBE strict=''")
  }
}
