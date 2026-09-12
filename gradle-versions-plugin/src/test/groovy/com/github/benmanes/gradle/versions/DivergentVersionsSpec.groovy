package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import groovy.xml.XmlParser
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.IgnoreIf
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for printing the projects behind a coordinate whose declared versions diverge
 * across an aggregated build.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1032')
final class DivergentVersionsSpec extends Specification {
  private static final String GUICE_URL = '     https://code.google.com/p/google-guice/'

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
    return TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  def 'Prints the projects behind a divergent coordinate'() {
    given:
    writeBuild('', "implementation 'com.google.guava:guava:15.0'")
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.guava:guava:16.0'
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
    result.output.contains(" - com.google.guava:guava:16.0${nl}     declared in :app")
    result.output.contains(
      " - com.google.guava:guava [15.0 -> 16.0]${nl}     declared in root project")
    result.output.count('declared in') == 2
  }

  def 'Prints every project that declared the shared version'() {
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

  def 'Prints every project of a list at the elision threshold'() {
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
     - com.google.guava:guava [15.0 -> 16.0]
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

  def 'Prints the projects in the file reports'() {
    given:
    writeBuild('', "implementation 'com.google.guava:guava:15.0'")
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.guava:guava:16.0'
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

  /**
   * Writes an aggregated build with one declared version of a module across its projects, each
   * rejecting different candidates for it, so that a different latest version is found in each.
   */
  private void writeSplitBuild(Map<String, String> rejectByProject, String declaredVersion = '2.0') {
    def subprojects = rejectByProject.keySet().findAll { it != ':' }
    testProjectDir.newFile('settings.gradle') <<
      (subprojects.empty ? '' : "include ${subprojects.collect { "'$it'" }.join(', ')}")
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          apply plugin: 'java'
          apply plugin: 'io.github.ben-manes.versions'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }

          dependencies {
            implementation 'com.google.inject:guice:$declaredVersion'
          }

          dependencyUpdates {
            checkForGradleUpdate = false
          }
        }

        dependencyUpdates {
          rejectVersionIf { ${rejectByProject[':']} }
        }
      """.stripIndent()
    rejectByProject.each { project, reject ->
      if (project == ':') {
        return
      }
      testProjectDir.newFolder(project)
      testProjectDir.newFile("$project/build.gradle") <<
        """
          dependencyUpdates {
            rejectVersionIf { $reject }
          }
        """.stripIndent()
    }
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1032')
  def 'Splits the rows of one declared version with different latest versions'() {
    given:
    writeSplitBuild([':': 'false', 'app': "it.candidate.version == '3.1'"])

    when:
    def result = run([':dependencyUpdates', '--no-parallel'])
    def nl = System.lineSeparator()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      " - com.google.inject:guice [2.0 -> 3.0]${nl}$GUICE_URL${nl}     declared in :app")
    result.output.contains(
      " - com.google.inject:guice [2.0 -> 3.1]${nl}$GUICE_URL${nl}     declared in root project")
  }

  def 'Splits the rows into their own sections when only one is outdated'() {
    given:
    writeSplitBuild([':': 'false', 'app': "it.candidate.version != '2.0'"])

    when:
    def result = run(
      [':dependencyUpdates', '-DoutputFormatter=plain,json,xml', '--no-parallel'])
    def nl = System.lineSeparator()
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    def xmlReport = new XmlParser()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.xml'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(" - com.google.inject:guice:2.0${nl}     declared in :app")
    result.output.contains(
      " - com.google.inject:guice [2.0 -> 3.1]${nl}$GUICE_URL${nl}     declared in root project")
    jsonReport.current.dependencies*.projects == [[':app']]
    jsonReport.outdated.dependencies*.projects == [[':']]
    jsonReport.outdated.dependencies[0].available.milestone == '3.1'
    xmlReport.current.dependencies.dependency[0].projects.project*.text() == [':app']
    xmlReport.outdated.dependencies.outdatedDependency[0].projects.project*.text() == [':']
  }

  def 'Collapses the split when the rule of the project asked for the report is the stricter'() {
    given: 'a root rejecting a version that neither subproject rejects'
    writeSplitBuild(
      [':': "it.candidate.version == '3.1'", 'app': 'false', 'lib': 'false'])

    when:
    def result = run([':dependencyUpdates', '--no-parallel'])

    then: 'the root caps the rows of both subprojects, leaving nothing to attribute'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(' - com.google.inject:guice [2.0 -> 3.0]')
    result.output.count('com.google.inject:guice') == 1
    !result.output.contains('declared in')
  }

  def 'Collapses the split on the configuration cache store and on the hit'() {
    given: 'a root rejecting a version that neither subproject rejects'
    writeSplitBuild(
      [':': "it.candidate.version == '3.1'", 'app': 'false', 'lib': 'false'])

    when:
    def store = run([':dependencyUpdates', '--no-parallel', '--configuration-cache'])
    def hit = run([':dependencyUpdates', '--no-parallel', '--configuration-cache'])

    then: 'the root caps the rows of both subprojects however the report reached its rules'
    store.output.contains(' - com.google.inject:guice [2.0 -> 3.0]')
    store.output.count('com.google.inject:guice') == 1
    !store.output.contains('declared in')

    and:
    hit.output.contains('Reusing configuration cache')
    hit.output.contains(' - com.google.inject:guice [2.0 -> 3.0]')
    hit.output.count('com.google.inject:guice') == 1
    !hit.output.contains('declared in')
  }

  def 'Reports every row when a rule the cache could not store throws at the report'() {
    given: 'a root rule calling a method declared in its own script, which cannot be stored in the entry'
    writeSplitBuild([':': 'false', 'app': 'false', 'lib': 'false'])
    new File(testProjectDir.root, 'build.gradle') <<
      """
        boolean isRejected(String version) {
          return version == '3.1'
        }

        dependencyUpdates {
          rejectVersionIf { isRejected(it.candidate.version) }
        }
      """.stripIndent()

    when:
    def store = run([':dependencyUpdates', '--no-parallel', '--configuration-cache'])
    def hit = run([':dependencyUpdates', '--no-parallel', '--configuration-cache'])

    then: 'the rule is reported as unapplied rather than failing the task'
    store.task(':dependencyUpdates').outcome == SUCCESS
    store.output.contains('Every dependency is left as the build that resolved it reported it')
    store.output.contains('com.google.inject:guice')

    and:
    hit.task(':dependencyUpdates').outcome == SUCCESS
    hit.output.contains('Every dependency is left as the build that resolved it reported it')
    hit.output.contains('com.google.inject:guice')
  }

  // Gradle 9 requires JVM 17.
  @IgnoreIf({ !GradleVersions.drivenBy(data.gradleVersion) })
  def 'Reports every row when a rule reading a script object reaches the report under Gradle #gradleVersion'() {
    given: 'the rule shape an aggregating report is documented to support, plus a subproject rule'
    writeSplitBuild(
      [':': "project.path == ':' && it.candidate.version == '3.1'", 'app': 'false', 'lib': 'false'])

    when:
    def store = TestKitRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments([':dependencyUpdates', '--no-parallel', '--configuration-cache'])
      .withPluginClasspath()
      .build()

    then: 'the task completes and reports the rows, rather than failing over what the entry dropped'
    store.task(':dependencyUpdates').outcome == SUCCESS
    store.output.contains('com.google.inject:guice')

    where:
    gradleVersion << ['8.4', GradleVersions.CURRENT]
  }

  def 'Collapses the split for an assigned resolutionStrategy under the configuration cache'() {
    given: 'the assignment form of the strategy at the root, and a subproject rule beneath it'
    writeSplitBuild([':': 'false', 'app': 'false', 'lib': 'false'])
    new File(testProjectDir.root, 'build.gradle') <<
      """
        dependencyUpdates {
          resolutionStrategy = {
            componentSelection {
              all { selection ->
                if (selection.candidate.version == '3.1') {
                  selection.reject('rejected by the test')
                }
              }
            }
          }
        }
      """.stripIndent()

    when:
    def store = run([':dependencyUpdates', '--no-parallel', '--configuration-cache'])
    def hit = run([':dependencyUpdates', '--no-parallel', '--configuration-cache'])

    then: 'the root caps the rows of both subprojects, as it does without the cache'
    store.task(':dependencyUpdates').outcome == SUCCESS
    store.output.contains(' - com.google.inject:guice [2.0 -> 3.0]')
    store.output.count('com.google.inject:guice') == 1

    and:
    hit.task(':dependencyUpdates').outcome == SUCCESS
    hit.output.contains(' - com.google.inject:guice [2.0 -> 3.0]')
    hit.output.count('com.google.inject:guice') == 1
  }

  def 'Collapses the split under the cache when the subprojects are configured before the root'() {
    given: "the rules of both subprojects declared before the root's own task is configured"
    testProjectDir.newFile('settings.gradle') << "include 'app', 'lib'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        subprojects {
          apply plugin: 'java'
          apply plugin: 'io.github.ben-manes.versions'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }

          dependencies {
            implementation 'com.google.inject:guice:2.0'
          }

          dependencyUpdates {
            checkForGradleUpdate = false
            rejectVersionIf { false }
          }
        }

        dependencyUpdates {
          checkForGradleUpdate = false
          rejectVersionIf { it.candidate.version == '3.1' }
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFolder('lib')

    when:
    def store = run([':dependencyUpdates', '--no-parallel', '--configuration-cache'])
    def hit = run([':dependencyUpdates', '--no-parallel', '--configuration-cache'])

    then: 'the root still caps the rows a subproject registered its rule ahead of it for'
    store.output.contains(' - com.google.inject:guice [2.0 -> 3.0]')
    store.output.count('com.google.inject:guice') == 1
    hit.output.contains('Reusing configuration cache')
    hit.output.count('com.google.inject:guice') == 1
  }

  def 'Collapses the split under the cache when a subproject rule precedes its own plugin'() {
    given: "a subproject declaring its rule through the task type, above the line applying the plugin"
    testProjectDir.newFile('settings.gradle') << "include 'app'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java'
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }

        dependencyUpdates {
          checkForGradleUpdate = false
          rejectVersionIf { it.candidate.version == '3.1' }
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        apply plugin: 'java'

        tasks.withType(com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask)
          .configureEach {
            rejectVersionIf { false }
          }

        apply plugin: 'io.github.ben-manes.versions'

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()

    when:
    def store = run([':dependencyUpdates', '--no-parallel', '--configuration-cache'])
    def hit = run([':dependencyUpdates', '--no-parallel', '--configuration-cache'])

    then: 'the rule set before the task was registered still marks the report above it'
    store.output.contains(' - com.google.inject:guice [2.0 -> 3.0]')
    store.output.count('com.google.inject:guice') == 1
    hit.output.contains('Reusing configuration cache')
    hit.output.count('com.google.inject:guice') == 1
  }

  def 'Keeps every row of a three way split in one section'() {
    given:
    writeSplitBuild([
      ':': 'false',
      'app': "it.candidate.version == '3.1'",
      'lib': "it.candidate.version in ['3.1', '3.0']",
    ])

    when:
    def result = run([':dependencyUpdates', '-DoutputFormatter=plain,json', '--no-parallel'])
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.count('com.google.inject:guice') == 3
    jsonReport.outdated.dependencies*.available.milestone.sort() == ['2.2', '3.0', '3.1']
    jsonReport.outdated.dependencies*.projects.flatten().sort() == [':', ':app', ':lib']
  }

  def 'Splits the rows in the exceeded section'() {
    given:
    writeSplitBuild(
      [':': "it.candidate.version == '3.1'", 'app': "it.candidate.version in ['3.1', '3.0']"],
      '3.1')

    when:
    def result = run([':dependencyUpdates', '-DoutputFormatter=plain,json', '--no-parallel'])
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then: 'each row shows the latest version found for it, not one shared across the key'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('[3.1 <- 3.0]')
    result.output.contains('[3.1 <- 2.2]')
    jsonReport.exceeded.dependencies*.latest.sort() == ['2.2', '3.0']
  }

  def 'Splits the row a platform bounds in its consumer and not in the platform itself'() {
    given: 'the platform declares the constraint that bounds the module in the project consuming it'
    testProjectDir.newFile('settings.gradle') << "include 'platform'"
    testProjectDir.newFolder('platform')
    testProjectDir.newFile('platform/build.gradle') <<
      """
        plugins { id 'java-platform' }
        dependencies {
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        subprojects {
          apply plugin: 'io.github.ben-manes.versions'
        }

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }

          tasks.dependencyUpdates {
            checkConstraints = true
            checkForGradleUpdate = false
            rejectVersionIf { !it.satisfiesDeclaredBound }
          }
        }

        dependencies {
          api platform(project(':platform'))
          api 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = run([':dependencyUpdates', '--no-parallel'])
    def nl = System.lineSeparator()

    then: 'the bounded row stays up to date rather than taking the platform row\'s upgrade'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      " - com.google.inject:guice:2.0${nl}" +
        "     constrained by the platform :platform in root project")
    result.output.contains(" - com.google.inject:guice [2.0 -> 3.1]${nl}$GUICE_URL")
  }

  def 'Keeps one row and no project names when the latest versions match'() {
    given:
    writeSplitBuild([':': 'false', 'app': 'false'])

    when:
    def result = run([':dependencyUpdates', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(' - com.google.inject:guice [2.0 -> 3.1]')
    result.output.count('com.google.inject:guice') == 1
    !result.output.contains('declared in')
  }

  def 'Prints no project in a single project report'() {
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
    // The resolvable configuration the second version was declared against is printed, which is where
    // it was declared rather than which project declared it.
    result.output.contains("declared in the 'second' configuration")
    !result.output.contains('declared in root project')
  }
}
