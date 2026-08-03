package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import groovy.xml.XmlParser
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for marking a dependency that only a plugin's lazy action contributes, with no
 * project declaring it, so that a version the build never set (e.g. jacoco's tool version) does
 * not read as a resolution bug.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1028')
final class ContributedDependencySpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  /** A 'tool' configuration whose guava dependency only {@code defaultDependencies} contributes. */
  private static String toolConfiguration(String version = '15.0') {
    """
      configurations.create('tool') {
        canBeResolved = true
        canBeConsumed = false
      }
      configurations.tool.defaultDependencies { deps ->
        deps.add(project.dependencies.create('com.google.guava:guava:$version'))
      }
    """.stripIndent()
  }

  private void writeBuild(String projectBody) {
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        $projectBody

        dependencyUpdates {
          checkForGradleUpdate = false
        }
      """.stripIndent()
  }

  private def run(List<String> arguments) {
    return GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  def 'Marks a dependency that only a plugin contributes'() {
    given:
    writeBuild(toolConfiguration())

    when:
    def result = run(['dependencyUpdates'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(" - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}     contributed by a plugin into the 'tool' configuration")
    result.output.count('contributed by a plugin') == 1
  }

  def 'Reports the mark in the file reports'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        dependencies {
          implementation 'com.google.inject:guice:3.1'
        }

        ${toolConfiguration()}
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=json,xml,html'])
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    def xmlReport = new XmlParser()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.xml'))
    def htmlReport = new File(testProjectDir.root, 'build/dependencyUpdates/report.html').text

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    jsonReport.outdated.dependencies[0].contributed == true
    jsonReport.outdated.dependencies[0].configurations == ['tool']
    !jsonReport.current.dependencies[0].containsKey('contributed')
    !jsonReport.current.dependencies[0].containsKey('configurations')
    xmlReport.outdated.dependencies.outdatedDependency[0].contributed.text() == 'true'
    xmlReport.outdated.dependencies.outdatedDependency[0].configurations.configuration
      *.text() == ['tool']
    !xmlReport.current.dependencies.dependency[0].children()*.name().contains('contributed')
    !xmlReport.current.dependencies.dependency[0].children()*.name().contains('configurations')
    htmlReport.contains("contributed by a plugin into the 'tool' configuration")
  }

  def 'Omits the mark when the build also declares the version'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }

        ${toolConfiguration()}
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=json'])
    def report = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(' - com.google.guava:guava [15.0 -> 16.0-rc1]')
    !result.output.contains('contributed by a plugin')
    !report.outdated.dependencies[0].containsKey('contributed')
  }

  def 'Names the contributing project behind a divergent version'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'"
    writeBuild(
      """
        apply plugin: 'java'

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent())
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        ${toolConfiguration('16.0-rc1')}
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '--no-parallel'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(" - com.google.guava:guava:16.0-rc1${nl}     contributed by a plugin into the 'tool' configuration in :app")
    result.output.contains(
      " - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}     declared in root project")
    result.output.count('contributed by a plugin') == 1
  }

  def 'Omits the mark when the build declares what a plugin filled a sibling with'() {
    given:
    writeBuild(
      """
        configurations.create('helper')
        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
          extendsFrom configurations.helper
        }
        configurations.helper.defaultDependencies { deps ->
          deps.add(project.dependencies.create('com.google.guava:guava:15.0'))
        }

        dependencies {
          tool 'com.google.guava:guava:15.0'
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(' - com.google.guava:guava [15.0 -> 16.0-rc1]')
    !result.output.contains('contributed by a plugin')
  }

  def 'Names the configuration a contributed constraint was declared against'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        def listener = new org.gradle.api.artifacts.DependencyResolutionListener() {
          void beforeResolve(ResolvableDependencies dependencies) {
            project.dependencies.constraints.add(
              'implementation', 'com.google.guava:guava:15.0')
            gradle.removeListener(this)
          }

          void afterResolve(ResolvableDependencies dependencies) { }
        }
        gradle.addListener(listener)

        dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      " - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}     contributed by a plugin into the 'implementation' configuration")
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1055')
  def 'Names the resolvable configuration a dependency was declared directly against'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        configurations.create('pluginClasspath') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          pluginClasspath 'com.google.guava:guava:15.0'
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      " - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}     declared in the 'pluginClasspath' configuration")
    !result.output.contains('contributed by a plugin')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1055')
  def 'Names the configuration when another resolvable one also reaches the dependency'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        configurations.create('pluginClasspath') {
          canBeResolved = true
          canBeConsumed = false
        }
        configurations.compileClasspath.extendsFrom(configurations.pluginClasspath)

        dependencies {
          pluginClasspath 'com.google.guava:guava:15.0'
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      " - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}     declared in the 'pluginClasspath' configuration")
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1055')
  def 'Names a stock configuration that is declarable and resolvable'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        dependencies {
          annotationProcessor 'com.google.guava:guava:15.0'
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      " - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}     declared in the 'annotationProcessor' configuration")
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1055')
  def 'Leaves an ordinary dependency without a configuration'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(' - com.google.guava:guava [15.0 -> 16.0-rc1]')
    !result.output.contains('declared in the')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1055')
  def 'Leaves a buildscript classpath dependency without a configuration'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          repositories {
            mavenCentral()
          }
          dependencies {
            classpath 'com.google.code.findbugs:jsr305:3.0.1'
          }
        }

        plugins {
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencyUpdates {
          checkForGradleUpdate = false
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(' - com.google.code.findbugs:jsr305 [3.0.1 -> ')
    !result.output.contains('declared in the')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1055')
  def 'Omits the configuration when another project declares the dependency ordinarily'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'\ninclude 'lib'"
    writeBuild(
      """
        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent())
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        configurations.create('pluginClasspath') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          pluginClasspath 'com.google.guava:guava:15.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        apply plugin: 'java'

        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(' - com.google.guava:guava [15.0 -> 16.0-rc1]')
    !result.output.contains('declared in the')
  }

  def 'Names every configuration a plugin contributed the coordinate into'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'\ninclude 'lib'"
    writeBuild(
      """
        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent())
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') << toolConfiguration()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        configurations.create('helper') {
          canBeResolved = true
          canBeConsumed = false
        }
        configurations.helper.defaultDependencies { deps ->
          deps.add(project.dependencies.create('com.google.guava:guava:15.0'))
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '--no-parallel'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      " - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}     contributed by a plugin into the 'helper' and 'tool' configurations")
    result.output.count('contributed by a plugin') == 1
  }

  def 'Marks the dependency when the build read the dependencies while configuring'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'\ninclude 'lib'"
    ['app', 'lib'].each {
      testProjectDir.newFolder(it)
      testProjectDir.newFile("$it/build.gradle") << ''
    }
    writeBuild(
      """
        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }

          ${toolConfiguration()}

          // Reading the incoming dependencies runs the actions that contribute them, so a build
          // doing so while configuring leaves the dependencies indistinguishable from declared ones
          // to anything reading the configuration afterwards.
          afterEvaluate {
            configurations.findAll { it.canBeResolved }.any { configuration ->
              configuration.incoming.dependencies.any { it.name == 'guice' }
            }
          }
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates', '--no-parallel'])
    def partials = new File(testProjectDir.root, 'build/dependencyUpdates/partials')
      .listFiles().collect { it.text }
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    partials.size() == 3
    partials.every { it.contains('"contributed":true') }
    result.output.contains(" - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}     contributed by a plugin into the 'tool' configuration")
  }

  def 'Leaves a buildscript dependency unmarked despite a same named project configuration'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          repositories {
            mavenCentral()
          }
          dependencies {
            classpath 'com.google.code.findbugs:jsr305:3.0.1'
          }
        }

        plugins {
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('classpath') {
          canBeResolved = true
          canBeConsumed = false
        }
        configurations.classpath.defaultDependencies { deps ->
          deps.add(project.dependencies.create('com.google.inject:guice:2.0'))
        }

        dependencyUpdates {
          checkForGradleUpdate = false
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      " - com.google.inject:guice [2.0 -> 3.1]${nl}     https://code.google.com/p/google-guice/" +
        "${nl}     contributed by a plugin into the 'classpath' configuration")
    result.output.count('contributed by a plugin') == 1
  }

  def 'Reports a contributed dependency identically across consecutive runs'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'\ninclude 'lib'"
    writeBuild(
      """
        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent())
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') << toolConfiguration()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        configurations.create('helper') {
          canBeResolved = true
          canBeConsumed = false
        }
        configurations.helper.defaultDependencies { deps ->
          deps.add(project.dependencies.create('com.google.guava:guava:15.0'))
        }
      """.stripIndent()

    when:
    run(['dependencyUpdates', '-DoutputFormatter=text,json,xml'])
    def firstReports = ['txt', 'json', 'xml'].collectEntries {
      [(it): new File(testProjectDir.root, "build/dependencyUpdates/report.$it").text]
    }
    def result = run(['dependencyUpdates', '-DoutputFormatter=text,json,xml'])
    def secondReports = ['txt', 'json', 'xml'].collectEntries {
      [(it): new File(testProjectDir.root, "build/dependencyUpdates/report.$it").text]
    }

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    firstReports['txt'].contains("contributed by a plugin into the 'helper' and 'tool' configurations")
    secondReports == firstReports
  }

  def 'Logs the disagreeing projects when only some contribute a version'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'app'"
    writeBuild(
      """
        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        configurations.create('declared') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          declared 'com.google.guava:guava:15.0'
        }
      """.stripIndent())
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') << toolConfiguration()

    when:
    def result = run(['dependencyUpdates', '--info', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      'The projects disagree on whether a plugin contributed com.google.guava:guava, ' +
        'so it is left unmarked: contributed by :app')
    !result.output.contains('contributed by a plugin')
  }

  def 'Marks a contributed dependency that is already at the latest version'() {
    given:
    writeBuild(toolConfiguration('16.0-rc1'))

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=text,json'])
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      " - com.google.guava:guava:16.0-rc1${nl}     contributed by a plugin into the 'tool' configuration")
    jsonReport.current.dependencies[0].contributed == true
    jsonReport.current.dependencies[0].configurations == ['tool']
  }

  def 'Marks a contributed dependency that exceeds the version found'() {
    given:
    writeBuild(toolConfiguration('99.0-SNAPSHOT'))

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=text,json'])
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      " - com.google.guava:guava [99.0-SNAPSHOT <- 16.0-rc1]${nl}     contributed by a plugin into the 'tool' configuration")
    jsonReport.exceeded.dependencies[0].contributed == true
    jsonReport.exceeded.dependencies[0].configurations == ['tool']
  }

  def 'Marks a contributed dependency that fails to resolve'() {
    given:
    writeBuild(
      """
        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }
        configurations.tool.defaultDependencies { deps ->
          deps.add(project.dependencies.create('com.github.ben-manes:unresolvable:1.0'))
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=json'])
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    jsonReport.unresolved.dependencies[0].contributed == true
    jsonReport.unresolved.dependencies[0].configurations == ['tool']
  }

  def 'Keeps the report byte-identical without contributed dependencies'() {
    given:
    writeBuild(
      """
        apply plugin: 'java'

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent())

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=text,json,xml'])
    def reportFile = new File(testProjectDir.root, 'build/dependencyUpdates/report.txt')
    def expected =
      """
    ------------------------------------------------------------
    : Project Dependency Updates (report to plain text file)
    ------------------------------------------------------------

    The following dependencies have later milestone versions:
     - com.google.inject:guice [2.0 -> 3.1]
         https://code.google.com/p/google-guice/
      """.stripIndent().replace('\r', '').replace('\n', System.lineSeparator())
    def jsonReport = new File(testProjectDir.root, 'build/dependencyUpdates/report.json')
    def xmlReport = new File(testProjectDir.root, 'build/dependencyUpdates/report.xml')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    reportFile.text == expected
    !jsonReport.text.contains('"contributed"')
    !xmlReport.text.contains('contributed')
  }
}
