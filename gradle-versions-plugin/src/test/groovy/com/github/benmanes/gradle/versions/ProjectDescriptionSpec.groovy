package com.github.benmanes.gradle.versions

import static com.github.benmanes.gradle.versions.updates.DependencyUpdatesReporterKt.reporterFor
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.OutputFormatterArgument
import com.github.benmanes.gradle.versions.updates.PartialStatus
import com.github.benmanes.gradle.versions.updates.ReportRules
import com.github.benmanes.gradle.versions.updates.ReportRulesKt
import com.github.benmanes.gradle.versions.updates.UnresolvedInfo
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.json.JsonSlurper
import groovy.xml.XmlParser
import org.gradle.api.Action
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for the POM description reported for each dependency, through a build for what
 * is read from the POM and against the reporter for which status of a module supplies it.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/266')
final class ProjectDescriptionSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()

  private static PartialStatus status(String declaredVersion, String latestVersion, String description,
      String projectPath = null, String projectUrl = null, UnresolvedInfo unresolved = null) {
    return new PartialStatus('com.example', 'widget', declaredVersion, null, latestVersion, projectUrl,
      unresolved, false, [], projectPath, [], [], false, null, [], false, null, false, null, null, description)
  }

  private static Result reportOf(List<PartialStatus> statuses) {
    def project = ProjectBuilder.builder().build()
    def captured = null
    def outputFormatter = [execute: { result -> captured = result }] as Action<Result>
    reporterFor(statuses, project.path, project.logger, 'milestone',
      new OutputFormatterArgument.CustomAction(outputFormatter), project.file('build'), 'report',
      false, 'https://services.gradle.org/versions/', RELEASE_CANDIDATE.id).write()
    return captured
  }

  private static ReportRules rejecting(String version) {
    def strategy = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection ->
          if (selection.candidate.version == version) {
            selection.reject('rejected')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>
    return new ReportRules(strategy, Logging.getLogger(ProjectDescriptionSpec), 'milestone', false, null, null)
  }

  def 'Reports the description of the latest version in the json and xml reports'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${getClass().getResource('/maven/').toURI()}'
          }
        }

        dependencies {
          implementation 'com.example:described-widget:1.0'
          implementation 'com.example:placeholder-description:1.0'
          implementation 'com.google.inject:guice:2.0'
        }

        dependencyUpdates {
          checkForGradleUpdate = false
          outputFormatter = 'json,xml'
        }
      """.stripIndent()

    when:
    def result = TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def reportDir = new File(testProjectDir.root, 'build/dependencyUpdates')
    def json = new JsonSlurper().parse(new File(reportDir, 'report.json'))
    def xml = new XmlParser().parse(new File(reportDir, 'report.xml'))

    then: 'the latest version is described on one line, and a parent POM or a placeholder is not taken for it'
    result.task(':dependencyUpdates').outcome == SUCCESS
    def widget = json.outdated.dependencies.find { it.name == 'described-widget' }
    widget.description == 'The described-widget that replaced the first.'
    !json.outdated.dependencies.find { it.name == 'guice' }.containsKey('description')
    !json.current.dependencies.find { it.name == 'placeholder-description' }.containsKey('description')

    and:
    def outdated = xml.outdated.dependencies.outdatedDependency
    outdated.find { it.name.text() == 'described-widget' }.description.text() ==
      'The described-widget that replaced the first.'
    outdated.find { it.name.text() == 'guice' }.description.isEmpty()
  }

  def 'Reports the description of the version the row reports, whatever order it was resolved in'() {
    given: 'one configuration resolves a later version than another'
    def statuses = [
      status('1.0', '2.0', 'The second widget.'),
      status('1.0', '1.0', 'The first widget.'),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    result.outdated.dependencies*.description == ['The second widget.']

    where:
    reversed << [false, true]
  }

  def 'Reports each split row with the description of its own version'() {
    given: 'two projects declare one version and resolve it to different versions'
    def statuses = [
      status('2.0', '3.1', 'Widget 3.1', ':app'),
      status('2.0', '3.0', 'Widget 3.0', ':lib'),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    result.outdated.dependencies.collectEntries { [it.available.milestone, it.description] } ==
      ['3.0': 'Widget 3.0', '3.1': 'Widget 3.1']

    where:
    reversed << [false, true]
  }

  def 'Reports no description where the POM of the latest version has none'() {
    given: 'the later version is published without a description'
    def statuses = [
      status('1.0', '2.0', missing),
      status('1.0', '1.0', 'The first widget.'),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    result.outdated.dependencies*.description == [null]

    where:
    [missing, reversed] << [[null, ''], [false, true]].combinations()
  }

  def 'Reports the description of a version where only the status whose url sorts first has one'() {
    given: 'two repositories serve the version, the one with the later url without a description'
    def statuses = [
      status('2.0', '2.0', null, null, 'https://b.example'),
      status('2.0', '2.0', 'The widget.', null, 'https://a.example'),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    result.current.dependencies*.projectUrl == ['https://b.example']
    result.current.dependencies*.description == ['The widget.']

    where:
    reversed << [false, true]
  }

  def 'Reports an unresolved row with the description of the latest version of its module'() {
    given: 'the rules reject every candidate for one project'
    def rejected = new UnresolvedInfo('com.example', 'widget', '3.0', 'Rejected', '1.0')
    def statuses = [
      status('1.0', '2.0', 'The second widget.', ':app'),
      status('1.0', 'none', null, ':lib', null, rejected),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    result.unresolved.dependencies*.description == ['The second widget.']

    where:
    reversed << [false, true]
  }

  def 'Reports a row moved by the report rules with the description read for its version in another list'() {
    given: 'a row moved off a rejected version, and a row in the other list at the version it moves to'
    // Sorts after the other, so that the tie between the two rows at one version is not what picks it.
    def moved = status('5.0', '5.7-alpha1', 'The widget, unreleased.', ':app')
    def resolved = status('5.0', '5.6.2', 'The stable widget.', ':app')
    def candidates = [':app': ['com.example:widget:5.7-alpha1', 'com.example:widget:5.6.2']]
    def rules = rejecting('5.7-alpha1')
    def urls = ReportRulesKt.urlByVersion([moved, resolved])
    def descriptions = ReportRulesKt.descriptionByVersion([moved, resolved])
    def lists = reversed ? [[resolved], [moved]] : [[moved], [resolved]]

    when:
    def result = reportOf(lists.collectMany { rules.applyTo(it, candidates, urls, descriptions) })

    then:
    result.outdated.dependencies*.available*.milestone == ['5.6.2']
    result.outdated.dependencies*.description == ['The stable widget.']

    where:
    reversed << [false, true]
  }

  def 'Keeps the description of a row moved by the report rules where none was read for its version'() {
    given: 'a row moved off a rejected version that no other row reports'
    def moved = status('5.0', '5.7-alpha1', 'The widget.', ':app')
    def candidates = [':app': ['com.example:widget:5.7-alpha1', 'com.example:widget:5.6.2']]

    when:
    def result = reportOf(rejecting('5.7-alpha1').applyTo([moved], candidates))

    then:
    result.outdated.dependencies*.available*.milestone == ['5.6.2']
    result.outdated.dependencies*.description == ['The widget.']
  }
}
