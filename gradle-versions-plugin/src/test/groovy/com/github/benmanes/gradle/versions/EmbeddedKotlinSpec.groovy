package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1110')
final class EmbeddedKotlinSpec extends Specification {
  private static final List<String> PINNED_ROWS = [
    'org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin',
    'org.jetbrains.kotlin:kotlin-reflect',
    'org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable',
    'org.jetbrains.kotlin:kotlin-stdlib',
  ]
  private static final String LEGACY_PLUGINS = 'org.gradle.kotlin:gradle-kotlin-dsl-plugins'

  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()

  private void kotlinDslBuild(String configured = '', String plugin = '`kotlin-dsl`') {
    testProjectDir.newFile('build.gradle.kts') <<
      """
        import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

        plugins {
          ${plugin}
          id("io.github.ben-manes.versions")
        }

        repositories {
          mavenCentral()
        }

        ${configured}
      """.stripIndent()
  }

  private String report(String... arguments) {
    def result = TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(['dependencyUpdates'] + arguments.toList())
      .withPluginClasspath()
      .build()
    assert result.task(':dependencyUpdates').outcome == SUCCESS
    return result.output
  }

  /** Whether the report prints an entry for the module, as an outdated or an up to date row. */
  private static boolean hasRow(String output, String module) {
    return output =~ /(?m)^ - ${java.util.regex.Pattern.quote(module)}[ :]/
  }

  def 'the versions Gradle sets for its embedded Kotlin are left out of the report'() {
    given:
    kotlinDslBuild()

    when:
    def output = report()

    then:
    PINNED_ROWS.every { !hasRow(output, it) }
    output.contains("4 entries set by Gradle's embedded Kotlin were left out. Run with --check-embedded-kotlin to see them.")
    // A classpath the Kotlin Gradle Plugin fills for its own tooling is left to the configuration
    // filters.
    hasRow(output, 'org.jetbrains.kotlin:kotlin-sam-with-receiver-compiler-plugin-embeddable')
  }

  def 'the versions Gradle sets for embedded-kotlin are left out of the report'() {
    given:
    kotlinDslBuild('', '`embedded-kotlin`')

    when:
    def output = report()

    then:
    !hasRow(output, 'org.gradle.kotlin.embedded-kotlin:org.gradle.kotlin.embedded-kotlin.gradle.plugin')
    !hasRow(output, 'org.jetbrains.kotlin:kotlin-reflect')
    !hasRow(output, 'org.jetbrains.kotlin:kotlin-stdlib')
  }

  @Unroll
  def 'the kotlin-dsl plugins at the version paired with Gradle are left out where no plugin applies them (#description)'() {
    given:
    testProjectDir.newFile('build.gradle.kts') <<
      '''
        buildscript {
          repositories {
            gradlePluginPortal()
          }
          dependencies {
            classpath("org.gradle.kotlin:gradle-kotlin-dsl-plugins:${org.gradle.kotlin.dsl.support.expectedKotlinDslPluginsVersion}")
          }
        }

        plugins {
          id("io.github.ben-manes.versions")
        }
      '''.stripIndent()

    when:
    def output = report(*arguments)

    then:
    hasRow(output, LEGACY_PLUGINS) == reported
    output.contains("1 entry set by Gradle's embedded Kotlin was left out. Run with --check-embedded-kotlin to see it.") == !reported

    where:
    description           | arguments                   | reported
    'by default'          | []                          | false
    'command line option' | ['--check-embedded-kotlin'] | true
  }

  @Unroll
  def 'the versions Gradle sets for its embedded Kotlin are reported (#description)'() {
    given:
    kotlinDslBuild(configured)

    when:
    def output = report(*arguments)

    then:
    PINNED_ROWS.every { hasRow(output, it) }
    !output.contains("set by Gradle's embedded Kotlin")

    where:
    description           | configured                                                                         | arguments
    'configured'          | 'tasks.named<DependencyUpdatesTask>("dependencyUpdates") { checkEmbeddedKotlin = true }' | []
    'command line option' | ''                                                                                 | ['--check-embedded-kotlin']
  }

  def 'the command line option leaves the versions out where the build reports them'() {
    given:
    kotlinDslBuild('tasks.named<DependencyUpdatesTask>("dependencyUpdates") { checkEmbeddedKotlin = true }')

    when:
    def output = report('--no-check-embedded-kotlin')

    then:
    PINNED_ROWS.every { !hasRow(output, it) }
  }

  def 'a Kotlin module declared at another version is reported'() {
    given:
    kotlinDslBuild(
      '''
        dependencies {
          implementation("org.jetbrains.kotlin:kotlin-reflect:1.8.0")
        }
      ''')

    when:
    def output = report()

    then:
    output.contains('org.jetbrains.kotlin:kotlin-reflect [1.8.0 -> ')
    !output.contains("org.jetbrains.kotlin:kotlin-reflect [${embeddedKotlinVersion(output)} -> ")
  }

  def 'the kotlin-dsl plugin at another version is reported'() {
    given:
    kotlinDslBuild(
      '''
        repositories {
          gradlePluginPortal()
        }

        dependencies {
          compileOnly("org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin:4.0.0")
        }
      ''')

    when:
    def output = report()

    then:
    output.contains('org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin [4.0.0 -> ')
  }

  def 'a Kotlin module the build declares at the embedded version is reported'() {
    given:
    kotlinDslBuild(
      '''
        dependencies {
          implementation("org.jetbrains.kotlin:kotlin-reflect:$embeddedKotlinVersion")
        }
      ''')

    when:
    def output = report()

    then:
    hasRow(output, 'org.jetbrains.kotlin:kotlin-reflect')
    !hasRow(output, 'org.jetbrains.kotlin:kotlin-stdlib')
  }

  def 'the kotlin-dsl plugins declared as a library at the version paired with Gradle are reported'() {
    given:
    kotlinDslBuild(
      '''
        repositories {
          gradlePluginPortal()
        }

        dependencies {
          implementation("org.gradle.kotlin:gradle-kotlin-dsl-plugins:${org.gradle.kotlin.dsl.support.expectedKotlinDslPluginsVersion}")
        }
      ''')

    when:
    def output = report()

    then:
    hasRow(output, LEGACY_PLUGINS)
  }

  @Unroll
  def 'the setting on the including build applies to an included build under the configuration cache (reported: #reported)'() {
    given:
    testProjectDir.newFile('settings.gradle.kts') << 'includeBuild("build-logic")'
    testProjectDir.newFile('build.gradle.kts') <<
      """
        import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

        plugins {
          java
          id("io.github.ben-manes.versions")
        }

        dependencies {
          dependencyUpdatesAggregation("com.example:build-logic:1.0")
        }

        tasks.named<DependencyUpdatesTask>("dependencyUpdates") { checkEmbeddedKotlin = $reported }
      """.stripIndent()
    testProjectDir.newFolder('build-logic')
    testProjectDir.newFile('build-logic/settings.gradle.kts') << 'rootProject.name = "build-logic"'
    testProjectDir.newFile('build-logic/build.gradle.kts') <<
      """
        buildscript {
          dependencies {
            classpath(files(${PluginClasspath.asFilesArgument().replace("'", '"')}))
          }
        }

        plugins {
          `kotlin-dsl`
        }

        apply(plugin = "io.github.ben-manes.versions")

        group = "com.example"
        version = "1.0"

        repositories {
          mavenCentral()
        }
      """.stripIndent()

    when:
    def first = report('--configuration-cache')
    def second = report('--configuration-cache')

    then:
    second.contains('Reusing configuration cache')
    [first, second].every { output -> PINNED_ROWS.every { hasRow(output, it) == reported } }

    where:
    reported << [false, true]
  }

  def 'a Kotlin module the build constrains at the embedded version is reported'() {
    given:
    kotlinDslBuild(
      '''
        dependencies {
          constraints {
            implementation("org.jetbrains.kotlin:kotlin-reflect:$embeddedKotlinVersion")
          }
        }

        tasks.named<DependencyUpdatesTask>("dependencyUpdates") { checkConstraints = true }
      ''')

    when:
    def output = report()

    then:
    hasRow(output, 'org.jetbrains.kotlin:kotlin-reflect')
    !hasRow(output, 'org.jetbrains.kotlin:kotlin-stdlib')
  }

  def 'a Kotlin module the build declares in a configuration named after Kotlin is reported'() {
    given:
    kotlinDslBuild(
      '''
        val kotlinScripts by configurations.creating

        dependencies {
          kotlinScripts("org.jetbrains.kotlin:kotlin-reflect:$embeddedKotlinVersion")
        }
      ''')

    when:
    def output = report()

    then:
    hasRow(output, 'org.jetbrains.kotlin:kotlin-reflect')
  }

  def 'a Kotlin module the build declares on its buildscript classpath at the embedded version is reported'() {
    given:
    testProjectDir.newFile('build.gradle.kts') <<
      '''
        buildscript {
          repositories {
            mavenCentral()
          }

          dependencies {
            classpath("org.jetbrains.kotlin:kotlin-reflect:$embeddedKotlinVersion")
          }
        }

        plugins {
          `kotlin-dsl`
          id("io.github.ben-manes.versions")
        }

        repositories {
          mavenCentral()
        }
      '''.stripIndent()

    when:
    def output = report()

    then:
    hasRow(output, 'org.jetbrains.kotlin:kotlin-reflect')
    !hasRow(output, 'org.jetbrains.kotlin:kotlin-stdlib')
  }

  def 'a module declared with no version in another project is reported beside the version Gradle sets'() {
    given:
    testProjectDir.newFile('settings.gradle.kts') << 'include("app")'
    kotlinDslBuild()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle.kts') <<
      '''
        plugins {
          java
        }

        repositories {
          mavenCentral()
        }

        dependencies {
          implementation("org.jetbrains.kotlin:kotlin-stdlib")
        }
      '''.stripIndent()

    when:
    def output = report()

    then:
    output =~ /(?m)^ - org\.jetbrains\.kotlin:kotlin-stdlib$/
  }

  def 'a Kotlin module Gradle sets that fails to resolve is reported'() {
    given:
    testProjectDir.newFolder('empty')
    testProjectDir.newFile('build.gradle.kts') <<
      """
        plugins {
          `kotlin-dsl`
          id("io.github.ben-manes.versions")
        }

        repositories {
          maven { url = uri("${testProjectDir.root.toURI()}empty") }
        }
      """.stripIndent()

    when:
    def output = report()

    then:
    output =~ /(?m)^ - org\.jetbrains\.kotlin:kotlin-stdlib:\S+\n\s+Could not/
  }

  def 'a subproject report inherits the setting from the root task'() {
    given:
    testProjectDir.newFile('settings.gradle.kts') << 'include("app")'
    testProjectDir.newFile('build.gradle.kts') <<
      '''
        import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

        plugins {
          id("io.github.ben-manes.versions")
        }

        tasks.named<DependencyUpdatesTask>("dependencyUpdates") { checkEmbeddedKotlin = true }
      '''.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle.kts') <<
      '''
        plugins {
          `kotlin-dsl`
          id("io.github.ben-manes.versions")
        }

        repositories {
          mavenCentral()
        }
      '''.stripIndent()

    when:
    def result = TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(':app:dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.task(':app:dependencyUpdates').outcome == SUCCESS
    PINNED_ROWS.every { hasRow(result.output, it) }
  }

  def 'a project that does not apply kotlin-dsl reports the module at the embedded version'() {
    given:
    testProjectDir.newFile('settings.gradle.kts') << 'include("app")'
    kotlinDslBuild()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle.kts') <<
      '''
        plugins {
          java
        }

        repositories {
          mavenCentral()
        }

        dependencies {
          implementation("org.jetbrains.kotlin:kotlin-stdlib:$embeddedKotlinVersion")
        }
      '''.stripIndent()

    when:
    def output = report()

    then:
    hasRow(output, 'org.jetbrains.kotlin:kotlin-stdlib')
    !hasRow(output, 'org.jetbrains.kotlin:kotlin-reflect')
  }

  /** Returns the embedded Kotlin version, read from the row of a compiler plugin `kotlin-dsl` applies. */
  private static String embeddedKotlinVersion(String output) {
    def matcher = output =~ /(?m)^ - org\.jetbrains\.kotlin:kotlin-sam-with-receiver-compiler-plugin-embeddable(?: \[|:)([^ \]\n]+)/
    assert matcher.find()
    return matcher.group(1)
  }
}
