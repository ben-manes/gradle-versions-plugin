package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

final class ConstraintsSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  def "Show updates for an api dependency constraint"() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def "Does not override explicit dependency with constraint"() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:3.0'
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('com.google.inject:guice [3.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def "Does not show updates for an api dependency constraint when disabled"() {
    given:
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('No dependencies found.')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Do not show updates for a constraint that gradle added itself'() {
    given:
    ExpandoMetaClass.disableGlobally()
    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile <<
      """
        plugins {
            java
            id("io.github.ben-manes.versions")
        }

        tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
            checkConstraints = true
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    // Gradle constrains its own buildscript classpath, and which version it pins moves with the
    // gradle version, so no version may be reported rather than no particular one.
    !result.output.contains('org.apache.logging.log4j:log4j-core [')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def "Show updates for log4j-core even if the constraint added by gradle is ignored"() {
    given:
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    ExpandoMetaClass.disableGlobally()
    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile <<
      """
        plugins {
            java
            id("io.github.ben-manes.versions")
        }
        repositories {
            maven {
                url = uri("${mavenRepoUrl}")
          }
        }

        dependencies {
          constraints {
            implementation ("org.apache.logging.log4j:log4j-core:2.16.0")
          }
        }

        tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
            checkConstraints = true
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('org.apache.logging.log4j:log4j-core [2.16.0 -> ')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/802')
  def 'Show updates for a dependency held to a strict version by a constraint'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
          constraints {
            api('com.google.inject:guice') {
              version {
                strictly '2.0'
              }
            }
          }
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def "Show updates for a dependencies constraint in init scripts"() {
    given:
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
    ExpandoMetaClass.disableGlobally()
    buildFile = testProjectDir.newFile('build.gradle.kts')
    buildFile <<
      """
        plugins {
            java
            id("io.github.ben-manes.versions")
        }
        repositories {
            maven {
                url = uri("${mavenRepoUrl}")
          }
        }
        tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
            checkBuildEnvironmentConstraints = true
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('org.apache.logging.log4j:log4j-core [2.17.1 -> ')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/756')
  def "Skip the buildscript of a project that declares no repository"() {
    given:
    // Gradle constrains every project's buildscript classpath to its own log4j version. Neither
    // the empty middle project nor the leaf declares a repository to resolve that against, so
    // only the root, whose plugins block carries the plugin management repositories, can answer.
    testProjectDir.newFile('settings.gradle') << "include 'middle:leaf'"
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        plugins {
          id 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkBuildEnvironmentConstraints = true
        }
      """.stripIndent()
    testProjectDir.newFolder('middle', 'leaf')
    testProjectDir.newFile('middle/leaf/build.gradle') << "apply plugin: 'java'"

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('org.apache.logging.log4j:log4j-core [2.17.1 -> ')
    !result.output.contains('Failed to determine the latest version')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/756')
  def "Keep the buildscript of a root whose repositories come from plugin management"() {
    given:
    // The plugin management repositories land in the root's buildscript repositories, so a root
    // that takes its plugins from a plugins block alone is queried rather than skipped. The fixture
    // tops out at 2.17.0, below the 2.17.1 that Gradle constrains its classpath to, so the entry
    // reports in the exceed section, which is only printed when the query actually ran.
    testProjectDir.newFile('settings.gradle') <<
      """
        pluginManagement {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent()
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkBuildEnvironmentConstraints = true
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('org.apache.logging.log4j:log4j-core [2.17.1 <- 2.17.0]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/756')
  def "Report a buildscript repository that cannot answer for the classpath"() {
    given:
    // Declared but holding nothing, which is a misconfiguration rather than a project with no
    // script classpath of its own, so the failure is still the user's to see.
    def emptyRepo = testProjectDir.newFolder('empty-repository').toURI()
    testProjectDir.newFile('settings.gradle') << "include 'declared'"
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkBuildEnvironmentConstraints = true
        }
      """.stripIndent()
    testProjectDir.newFolder('declared')
    testProjectDir.newFile('declared/build.gradle') <<
      """
        buildscript {
          repositories {
            maven {
              url '${emptyRepo}'
            }
          }
        }

        apply plugin: 'java'
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('Failed to determine the latest version')
    result.output.contains('org.apache.logging.log4j:log4j-core')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/755')
  def "Report the reason a constraint was declared with"() {
    given: 'a dependency states its reason in the report, and a constraint should read the same'
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          constraints {
            api('com.google.inject:guice:2.0') {
              because 'a constraint reason'
            }
          }
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('a constraint reason')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def "Report a module the consumer declares for a version the platform supplies"() {
    given: 'log4j is a published BOM whose dependencyManagement names log4j-core'
    testProjectDir.newFile('settings.gradle') << "include 'platform'\n"
    testProjectDir.newFolder('platform')
    testProjectDir.newFile('platform/build.gradle') <<
      """
        plugins { id 'java-platform' }
        dependencies {
          constraints {
            api 'org.apache.logging.log4j:log4j-core:2.16.0'
          }
        }
      """.stripIndent()
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api $platform
          api 'org.apache.logging.log4j:log4j-core'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then: 'the versionless declaration is checked, at the version the platform supplied'
    result.output.contains('org.apache.logging.log4j:log4j-core [2.16.0 -> 2.17.0]')
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    platform << [
      "platform('org.apache.logging.log4j:log4j:2.16.0')",
      "enforcedPlatform('org.apache.logging.log4j:log4j:2.16.0')",
      "platform(project(':platform'))",
    ]
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def "Do not enumerate a consumed platform's own constraints"() {
    given: 'the platform constrains log4j-core, and the consumer never declares it'
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api platform('org.apache.logging.log4j:log4j:2.16.0')
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then: 'the platform itself is reported, the module it constrains is not'
    result.output.contains('org.apache.logging.log4j:log4j [2.16.0 -> 2.17.0]')
    !result.output.contains('log4j-core')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Names the platform project that imports an external platform'() {
    given: 'a chain of same-build platform projects, only the last of which imports the BOM'
    testProjectDir.newFile('settings.gradle') << "include 'app-platform', 'test-platform'\n"
    testProjectDir.newFolder('app-platform')
    testProjectDir.newFile('app-platform/build.gradle') <<
      """
        plugins { id 'java-platform' }
        javaPlatform {
          allowDependencies()
        }
        dependencies {
          api platform(project(':test-platform'))
        }
      """.stripIndent()
    testProjectDir.newFolder('test-platform')
    testProjectDir.newFile('test-platform/build.gradle') <<
      """
        plugins { id 'java-platform' }
        javaPlatform {
          allowDependencies()
        }
        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }
        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent()
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation platform(project(':app-platform'))
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then: 'the direct importer is named, not the first hop of the chain'
    result.output.contains('imported by the platform :test-platform\n')
    !result.output.contains('the platform :app-platform')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Names every platform project that imports the same external platform'() {
    given: 'two platform projects, each importing the same external bom'
    testProjectDir.newFile('settings.gradle') << "include 'platform-a', 'platform-b'\n"
    ['platform-a', 'platform-b'].each { name ->
      testProjectDir.newFolder(name)
      testProjectDir.newFile("$name/build.gradle") <<
        """
          plugins { id 'java-platform' }
          javaPlatform {
            allowDependencies()
          }
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
          dependencies {
            api platform('com.example:external-bom:1.0')
          }
        """.stripIndent()
    }
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation platform(project(':platform-a'))
          implementation platform(project(':platform-b'))
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.output.contains('imported by the platforms :platform-a, :platform-b\n')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Leaves out a platform project that consumes the module as a library'() {
    given: 'one platform project importing a pom module as a platform and another as a library'
    testProjectDir.newFile('settings.gradle') << "include 'platform-a', 'platform-b', 'platform-c'\n"
    ['platform-a': "api 'com.google.guava:guava:15.0'",
     'platform-b': "api platform('com.google.guava:guava:15.0')",
     'platform-c': "api platform('com.example:external-bom:1.0')"].each { name, declaration ->
      testProjectDir.newFolder(name)
      testProjectDir.newFile("$name/build.gradle") <<
        """
          plugins { id 'java-platform' }
          javaPlatform {
            allowDependencies()
          }
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
          dependencies {
            $declaration
          }
        """.stripIndent()
    }
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation platform(project(':platform-a'))
          implementation platform(project(':platform-b'))
          implementation platform(project(':platform-c'))
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then: 'only the project whose own edge resolved the platform variant is named'
    result.output.contains('imported by the platform :platform-b\n')
    !result.output.contains('imported by the platform :platform-a')
    !result.output.contains('imported by the platforms')
    result.output.contains('imported by the platform :platform-c\n')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }
}
