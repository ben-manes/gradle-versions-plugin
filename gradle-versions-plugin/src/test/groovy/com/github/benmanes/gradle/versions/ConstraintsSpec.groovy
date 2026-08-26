package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import groovy.xml.XmlParser
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
  def 'Show updates for a dependency bounded at a strict version by a constraint'() {
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
    // only the root, whose plugins block supplies the plugin management repositories, resolves it.
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
    // is reported in the exceed section, which is only printed when the query actually ran.
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
  def "Report a buildscript repository that cannot resolve the classpath"() {
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
  def 'Prints the platform project that imports an external platform'() {
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
  def 'Prints every platform project that imports the same external platform'() {
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
  def 'Prints every importer of an external platform they bound differently'() {
    given: 'two platform projects importing the same bom, one of them stating a preference'
    testProjectDir.newFile('settings.gradle') << "include 'platform-a', 'platform-b'\n"
    ['platform-a': "api platform('com.example:external-bom:1.0')",
     'platform-b': "api(platform('com.example:external-bom')) { version { prefer '1.0' } }"].each { name, declaration ->
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
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then: 'the differing bound costs neither project its place in the attribution'
    result.output.contains('com.example:external-bom [1.0 -> 2.0]')
    result.output.contains('imported by the platforms :platform-a, :platform-b\n')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Omits the mark of a module a platform project declares as a library'() {
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

    then: 'platform-a declares guava as a library, outranking the mark platform-b would otherwise carry'
    !result.output.contains('imported by the platform :platform-b')
    !result.output.contains('imported by the platform :platform-a')
    !result.output.contains('imported by the platforms')
    result.output.contains('imported by the platform :platform-c\n')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Does not skip the configuration when the platform scan throws under failOnVersionConflict'() {
    given: 'two platform projects import the same bom at different versions, conflicting transitively'
    testProjectDir.newFile('settings.gradle') << "include 'platform-a', 'platform-b'\n"
    ['platform-a': '1.0', 'platform-b': '2.0'].each { name, version ->
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
            api platform('com.example:external-bom:$version')
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

        configurations.all {
          resolutionStrategy.failOnVersionConflict()
        }

        dependencies {
          implementation platform(project(':platform-a'))
          implementation platform(project(':platform-b'))
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '--info')
      .withPluginClasspath()
      .build()

    then: 'the platform scan throwing does not sink the whole configuration'
    result.output.contains('Failed to resolve the platforms declared by')
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    !result.output.contains('Skipping configuration')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'Prints the platform project that constrains a versionless module'() {
    given: 'a platform project stating the bound in its own constraints block'
    testProjectDir.newFile('settings.gradle') << "include 'platform'\n"
    testProjectDir.newFolder('platform')
    testProjectDir.newFile('platform/build.gradle') <<
      """
        plugins { id 'java-platform' }
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
          implementation platform(project(':platform'))
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then: 'the constrained row names the platform project holding the version'
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('constrained by the platform :platform')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'Prints the external platform that constrains a versionless module'() {
    given: 'an external bom imported directly, bounding a versionless declaration'
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
          implementation platform('com.example:external-bom:1.0')
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then: 'the bom is named by its module, the version left to its own row'
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('constrained by the platform com.example:external-bom\n')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'Prints every platform that sets the same bound'() {
    given: 'two platform projects constraining the same module to the same version'
    testProjectDir.newFile('settings.gradle') << "include 'platform-a', 'platform-b'\n"
    ['platform-a', 'platform-b'].each { name ->
      testProjectDir.newFolder(name)
      testProjectDir.newFile("$name/build.gradle") <<
        """
          plugins { id 'java-platform' }
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

    then: 'both platforms are named, pluralized'
    result.output.contains('constrained by the platforms :platform-a, :platform-b')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'Marks the imported platform and the module it constrains on separate rows'() {
    given: 'a platform project importing the bom that bounds a versionless declaration'
    testProjectDir.newFile('settings.gradle') << "include 'platform'\n"
    testProjectDir.newFolder('platform')
    testProjectDir.newFile('platform/build.gradle') <<
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
          implementation platform(project(':platform'))
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then: 'the bom row carries the importer and the constrained row carries the bom'
    result.output.contains('imported by the platform :platform')
    result.output.contains('constrained by the platform com.example:external-bom\n')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'Omits the bound when a project outside the platforms declares the module'() {
    given: 'one project bounded by the platform and a sibling declaring the same version itself'
    testProjectDir.newFile('settings.gradle') << "include 'app', 'other', 'platform'\n"
    testProjectDir.newFolder('platform')
    testProjectDir.newFile('platform/build.gradle') <<
      """
        plugins { id 'java-platform' }
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
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        dependencies {
          implementation platform(project(':platform'))
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()
    testProjectDir.newFolder('other')
    testProjectDir.newFile('other/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        subprojects {
          if (name != 'platform') {
            apply plugin: 'java-library'
          }
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '--info', '--no-parallel')
      .withPluginClasspath()
      .build()

    then: 'the declaring project outranks the mark, which is reported rather than dropped silently'
    !result.output.contains('constrained by the platform')
    result.output.contains(
      "A project outside com.google.inject:guice's bounding platforms declares it, " +
        'so the platform mark is withheld: :other')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'Prints the constraining platform in the file reports'() {
    given: 'an external bom bounding a versionless declaration'
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
          implementation platform('com.example:external-bom:1.0')
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '-DoutputFormatter=json,xml')
      .withPluginClasspath()
      .build()
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    def xmlReport = new XmlParser()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.xml'))

    then: 'the machine readable reports carry the bound, and only where one was stated'
    result.task(':dependencyUpdates').outcome == SUCCESS
    def guice = jsonReport.outdated.dependencies.find { it.name == 'guice' }
    guice.constrainedBy == ['com.example:external-bom']
    def bom = jsonReport.outdated.dependencies.find { it.name == 'external-bom' }
    !bom.containsKey('constrainedBy')
    def guiceElement = xmlReport.outdated.dependencies.outdatedDependency.find {
      it.name.text() == 'guice'
    }
    guiceElement.constrainedBy.constraint*.text() == ['com.example:external-bom']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'Prints only the platform whose bound is the version reported'() {
    given: 'two platforms stating different bounds, of which the higher wins'
    testProjectDir.newFile('settings.gradle') << "include 'platform-low', 'platform-high'\n"
    ['platform-low': '2.0', 'platform-high': '3.0'].each { name, version ->
      testProjectDir.newFolder(name)
      testProjectDir.newFile("$name/build.gradle") <<
        """
          plugins { id 'java-platform' }
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
          dependencies {
            constraints {
              api 'com.google.inject:guice:${version}'
            }
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
          implementation platform(project(':platform-low'))
          implementation platform(project(':platform-high'))
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then: 'the platform whose bound lost is not named'
    result.output.contains('com.google.inject:guice [3.0 -> 3.1]')
    result.output.contains('constrained by the platform :platform-high')
    !result.output.contains('constrained by the platforms')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'Omits the platform mark when a drag pushes the version past the bound'() {
    given: 'a platform bound a transitive requirement overrides'
    testProjectDir.newFile('settings.gradle') << "include 'platform'\n"
    testProjectDir.newFolder('platform')
    testProjectDir.newFile('platform/build.gradle') <<
      """
        plugins { id 'java-platform' }
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
          implementation platform(project(':platform'))
          implementation 'com.example:guice-consumer:1.0'
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then: 'the row keeps the true declaration rather than a bound that did not decide it'
    result.output.contains('com.google.inject:guice [3.0 -> 3.1]')
    !result.output.contains('constrained by the platform')
    result.output.contains('declared in root project')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'Prints an external platform by its module, not the version it resolved to'() {
    given: 'an imported bom a library drags to a release the build never names'
    testProjectDir.newFile('settings.gradle') << "include 'platform-a'\n"
    testProjectDir.newFolder('platform-a')
    testProjectDir.newFile('platform-a/build.gradle') <<
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
          implementation platform(project(':platform-a'))
          implementation 'com.example:external-bom-consumer:1.0'
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then: 'the mark names a coordinate the bom row already carries the version for'
    result.output.contains('com.example:external-bom [1.0 -> 2.0]')
    result.output.contains('constrained by the platform com.example:external-bom\n')
    !result.output.contains('com.example:external-bom:2.0')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }
}
