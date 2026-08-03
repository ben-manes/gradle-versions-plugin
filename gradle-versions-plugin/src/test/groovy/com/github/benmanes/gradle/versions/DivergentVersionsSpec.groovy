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
 * A specification for naming the projects behind a coordinate whose declared versions diverge
 * across an aggregated build.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1032')
final class DivergentVersionsSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  private void writeBuild(String rootBuildscript = '', String rootDependencies = '',
      List<String> projects = ['app', 'lib']) {
    testProjectDir.newFile('settings.gradle') << "include ${projects.collect { "'$it'" }.join(', ')}"
    testProjectDir.newFile('build.gradle') <<
      """
        $rootBuildscript

        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          apply plugin: 'java'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        dependencies {
          $rootDependencies
        }

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

  def 'Names the projects behind a divergent coordinate'() {
    given:
    writeBuild('', "implementation 'com.google.guava:guava:15.0'")
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.guava:guava:16.0-rc1'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '--no-parallel'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(" - com.google.guava:guava:16.0-rc1${nl}     declared in :app")
    result.output.contains(
      " - com.google.guava:guava [15.0 -> 16.0-rc1]${nl}     declared in root project")
    result.output.count('declared in') == 2
  }

  def 'Lists every project that declared the shared version'() {
    given:
    writeBuild('', "implementation 'com.google.inject:guice:2.0'")
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.inject:guice:3.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.inject:guice:3.0'
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(' - com.google.inject:guice [3.0 -> 3.1]')
    result.output.contains(' - com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('     declared in :app, :lib')
    result.output.contains('     declared in root project')
  }

  def 'Elides a long project list in the human readable reports only'() {
    given:
    def projects = (1..7).collect { "p$it" }
    writeBuild('', "implementation 'com.google.inject:guice:2.0'", projects)
    projects.each { project ->
      testProjectDir.newFolder(project)
      testProjectDir.newFile("$project/build.gradle") <<
        """
          dependencies {
            implementation 'com.google.inject:guice:3.0'
          }
        """.stripIndent()
    }

    when:
    def result = run(
      ['dependencyUpdates', '-DoutputFormatter=plain,json,html', '--no-parallel'])
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    def htmlReport = new File(testProjectDir.root, 'build/dependencyUpdates/report.html').text

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('     declared in :p1, :p2, :p3, :p4, :p5 and 2 others')
    result.output.contains('     declared in root project')
    htmlReport.contains('declared in :p1, :p2, :p3, :p4, :p5 and 2 others')
    jsonReport.outdated.dependencies.find { it.version == '3.0' }.projects ==
      projects.collect { ":$it" }
  }

  def 'Names every project of a list at the elision threshold'() {
    given:
    def projects = (1..6).collect { "p$it" }
    writeBuild('', "implementation 'com.google.inject:guice:2.0'", projects)
    projects.each { project ->
      testProjectDir.newFolder(project)
      testProjectDir.newFile("$project/build.gradle") <<
        """
          dependencies {
            implementation 'com.google.inject:guice:3.0'
          }
        """.stripIndent()
    }

    when:
    def result = run(['dependencyUpdates', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('     declared in :p1, :p2, :p3, :p4, :p5, :p6')
    !result.output.contains('others')
  }

  def 'Aggregated projects that agree keep the report byte-identical'() {
    given:
    writeBuild()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '--no-parallel'])
    def reportFile = new File(testProjectDir.root, 'build/dependencyUpdates/report.txt')
    def expected =
      """
    ------------------------------------------------------------
    : Project Dependency Updates (report to plain text file)
    ------------------------------------------------------------

    The following dependencies have later milestone versions:
     - com.google.guava:guava [15.0 -> 16.0-rc1]
      """.stripIndent().replace('\r', '').replace('\n', System.lineSeparator())

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    reportFile.text == expected
    !reportFile.text.contains('declared in')
  }

  def 'Omits the projects key for a single version in the file reports'() {
    given:
    writeBuild()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '-DoutputFormatter=json', '--no-parallel'])
    def report = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    (report.current.dependencies + report.outdated.dependencies).every { !it.containsKey('projects') }
  }

  def 'Names the projects in the file reports'() {
    given:
    writeBuild('', "implementation 'com.google.guava:guava:15.0'")
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.guava:guava:16.0-rc1'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')

    when:
    def result = run(
      ['dependencyUpdates', '-DoutputFormatter=json,xml,html', '--no-parallel'])
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    def xmlReport = new XmlParser()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.xml'))
    def htmlReport = new File(testProjectDir.root, 'build/dependencyUpdates/report.html').text

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    jsonReport.outdated.dependencies[0].projects == [':']
    jsonReport.current.dependencies[0].projects == [':app']
    xmlReport.outdated.dependencies.outdatedDependency[0].projects.project*.text() == [':']
    htmlReport.contains('declared in root project')
    htmlReport.contains('declared in :app')
  }

  def 'Names no project in a single project report'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations {
          second
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
          second 'com.google.inject:guice:3.0'
        }

        dependencyUpdates {
          checkForGradleUpdate = false
        }
      """.stripIndent()

    when:
    def result = run(['dependencyUpdates', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.inject:guice [3.0 -> 3.1]')
    // The resolvable configuration the second version was declared against is named, which is where
    // it was declared rather than which project declared it.
    result.output.contains("declared in the 'second' configuration")
    !result.output.contains('declared in root project')
  }
}
