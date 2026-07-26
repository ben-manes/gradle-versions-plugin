package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A Groovy {@code rejectVersionIf} closure using the bare implicit-receiver form (plain
 * {@code candidate}, no closure parameter) must resolve {@code candidate} against the
 * selection rather than throwing {@code MissingPropertyException}, which otherwise fails every
 * dependency's version query and empties the whole report.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1009')
final class RejectVersionIfSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private List<File> pluginClasspath
  private String reportFolder
  private String classpathString
  private String mavenRepoUrl

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.getResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        "Did not find plugin classpath resource, run `testClasses` build task.")
    }

    pluginClasspath = pluginClasspathResource.readLines().collect { new File(it) }
    classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
    reportFolder = "${testProjectDir.root.path.replaceAll("\\\\", '/')}/build/dependencyUpdates"
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  private File writeBuildFile(String rejectVersionIfBody) {
    return writeScript(
      """
        tasks.named('dependencyUpdates').configure {
          outputFormatter = 'json'
          checkForGradleUpdate = false
          rejectVersionIf {
            $rejectVersionIfBody
          }
        }
        """)
  }

  private File writeScript(String body) {
    def file = testProjectDir.newFile('build.gradle')
    file <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }

        $body
        """.stripIndent()
    return file
  }

  def 'bare implicit receiver form resolves candidate without emptying the report'() {
    given:
    buildFile = writeBuildFile("candidate.version.toLowerCase().contains('-zzz')")

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    report.outdated.dependencies*.name == ['guava']
    report.outdated.dependencies[0].available.milestone == '16.0-rc1'
    report.unresolved.dependencies.isEmpty()
  }

  def 'explicit parameter form continues to resolve candidate'() {
    given:
    buildFile = writeBuildFile("s -> s.candidate.version.toLowerCase().contains('-zzz')")

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    report.outdated.dependencies*.name == ['guava']
    report.outdated.dependencies[0].available.milestone == '16.0-rc1'
    report.unresolved.dependencies.isEmpty()
  }

  def 'a build script property is not shadowed by a selection property of the same name'() {
    given:
    buildFile = writeScript('''
      ext.currentVersion = '16.0-rc1'

      tasks.named('dependencyUpdates').configure {
        outputFormatter = 'json'
        checkForGradleUpdate = false
        rejectVersionIf {
          candidate.version == currentVersion
        }
      }
      ''')

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text)

    then: 'the script property wins, so the only upgrade is rejected'
    result.task(':dependencyUpdates').outcome == SUCCESS
    report.outdated.dependencies.isEmpty()
    report.current.dependencies*.name == ['guava']
  }

  def 'two rejectVersionIf calls both apply'() {
    given: 'each filter targets a different dependency, so either alone leaves the other unfiltered'
    buildFile = writeScript('''
      dependencies {
        implementation 'com.google.inject:guice:2.0'
      }

      tasks.named('dependencyUpdates').configure {
        outputFormatter = 'json'
        checkForGradleUpdate = false
        rejectVersionIf {
          candidate.version == '16.0-rc1'
        }
        rejectVersionIf {
          candidate.version == '3.1'
        }
      }
      ''')

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text)

    then: 'guava has no update (its only candidate was rejected) and guice stops at 3.0'
    result.task(':dependencyUpdates').outcome == SUCCESS
    report.current.dependencies*.name == ['guava']
    report.outdated.dependencies*.name == ['guice']
    report.outdated.dependencies[0].available.milestone == '3.0'
  }

  def 'rejectVersionIf then resolutionStrategy both apply'() {
    given:
    buildFile = writeScript('''
      dependencies {
        implementation 'com.google.inject:guice:2.0'
      }

      tasks.named('dependencyUpdates').configure {
        outputFormatter = 'json'
        checkForGradleUpdate = false
        rejectVersionIf {
          candidate.version == '16.0-rc1'
        }
        resolutionStrategy {
          componentSelection {
            all {
              if (candidate.version == '3.1') {
                reject('rejected by componentSelection')
              }
            }
          }
        }
      }
      ''')

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text)

    then: 'the rejectVersionIf filter and the componentSelection rule both took effect'
    result.task(':dependencyUpdates').outcome == SUCCESS
    report.current.dependencies*.name == ['guava']
    report.outdated.dependencies*.name == ['guice']
    report.outdated.dependencies[0].available.milestone == '3.0'
  }

  def 'resolutionStrategy then rejectVersionIf both apply'() {
    given:
    buildFile = writeScript('''
      dependencies {
        implementation 'com.google.inject:guice:2.0'
      }

      tasks.named('dependencyUpdates').configure {
        outputFormatter = 'json'
        checkForGradleUpdate = false
        resolutionStrategy {
          componentSelection {
            all {
              if (candidate.version == '3.1') {
                reject('rejected by componentSelection')
              }
            }
          }
        }
        rejectVersionIf {
          candidate.version == '16.0-rc1'
        }
      }
      ''')

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text)

    then: 'the componentSelection rule and the rejectVersionIf filter both took effect'
    result.task(':dependencyUpdates').outcome == SUCCESS
    report.current.dependencies*.name == ['guava']
    report.outdated.dependencies*.name == ['guice']
    report.outdated.dependencies[0].available.milestone == '3.0'
  }

  def 'resolutionStrategy with no argument clears a previously registered rejectVersionIf'() {
    given:
    buildFile = writeScript('''
      dependencies {
        implementation 'com.google.inject:guice:2.0'
      }

      tasks.named('dependencyUpdates').configure {
        rejectVersionIf {
          candidate.version == '3.1'
        }
        resolutionStrategy()
      }
      ''')

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then: 'the reset escape hatch wins, so nothing is rejected'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  def 'the deprecated resolutionStrategy property assignment replaces a previously registered rejectVersionIf'() {
    given: 'the rejectVersionIf filter targets guava, so a survived guava rejection would prove it was clobbered'
    buildFile = writeScript('''
      dependencies {
        implementation 'com.google.inject:guice:2.0'
      }

      tasks.named('dependencyUpdates').configure {
        outputFormatter = 'json'
        checkForGradleUpdate = false
        rejectVersionIf {
          candidate.version == '16.0-rc1'
        }
        resolutionStrategy = {
          componentSelection {
            all {
              if (candidate.version == '3.1') {
                reject('rejected by componentSelection')
              }
            }
          }
        }
      }
      ''')

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text)

    then: 'the assignment replaced the guava filter rather than composing with it, so guava is outdated again'
    result.task(':dependencyUpdates').outcome == SUCCESS
    report.outdated.dependencies*.name.sort() == ['guava', 'guice']
    report.outdated.dependencies.find { it.name == 'guava' }.available.milestone == '16.0-rc1'
    report.outdated.dependencies.find { it.name == 'guice' }.available.milestone == '3.0'
  }

  def 'the closure supplied by the build script is left unmodified'() {
    given:
    buildFile = writeScript('''
      def filter = { candidate.version.toLowerCase().contains('-zzz') }

      tasks.named('dependencyUpdates').configure {
        outputFormatter = 'json'
        checkForGradleUpdate = false
        rejectVersionIf filter
        doLast {
          file('closure-state.txt').text = "${filter.resolveStrategy}|${filter.delegate.getClass().name}"
        }
      }
      ''')

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def closureState = new File(testProjectDir.root, 'closure-state.txt').text

    then: 'each selection gets its own copy, so concurrent evaluation cannot tear'
    result.task(':dependencyUpdates').outcome == SUCCESS
    closureState.startsWith("${Closure.OWNER_FIRST}|")
    !closureState.contains('ComponentSelectionWithCurrent')
  }
}
