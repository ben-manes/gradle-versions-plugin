package com.github.benmanes.gradle.versions

import static com.github.benmanes.gradle.versions.updates.DependencyUpdatesReporterKt.reporterFor
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE

import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesReporter
import com.github.benmanes.gradle.versions.updates.OutputFormatterArgument
import com.github.benmanes.gradle.versions.updates.PartialStatus
import com.github.benmanes.gradle.versions.updates.ReportRules
import com.github.benmanes.gradle.versions.updates.ReportRulesKt
import com.github.benmanes.gradle.versions.updates.UnresolvedInfo
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for the project url reported for a module whose statuses carry different urls,
 * exercised against the reporter rather than through a build.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/844')
final class ProjectUrlSpec extends Specification {
  private static final String PROJECT_URL = 'https://developer.android.com/jetpack/androidx/releases/compose-ui'

  private static PartialStatus status(String declaredVersion, String latestVersion, String projectUrl,
      String projectPath = null, UnresolvedInfo unresolved = null) {
    return new PartialStatus('androidx.compose.ui', 'ui', declaredVersion, null, latestVersion,
      projectUrl, unresolved, false, [], projectPath)
  }

  private static String urlOf(String version) {
    return "${PROJECT_URL}#${version}"
  }

  private static DependencyUpdatesReporter reporterOf(List<PartialStatus> statuses, Action<Result> outputFormatter) {
    def project = ProjectBuilder.builder().build()
    return reporterFor(statuses, project.path, project.logger, 'milestone',
      new OutputFormatterArgument.CustomAction(outputFormatter), project.file('build'), 'report',
      false, 'https://services.gradle.org/versions/', RELEASE_CANDIDATE.id)
  }

  private static Result reportOf(List<PartialStatus> statuses) {
    def captured = null
    reporterOf(statuses, [execute: { result -> captured = result }] as Action<Result>).write()
    return captured
  }

  def 'Reports the project url of the version the row reports, whatever order it was resolved in'() {
    given: 'one configuration resolves a later version than another'
    def statuses = [
      status('1.4.3', '1.6.2', urlOf('1.6.2')),
      status('1.4.3', '1.4.3', urlOf('1.4.3')),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    def outdated = result.outdated.dependencies
    outdated*.available*.milestone == ['1.6.2']
    outdated*.projectUrl == [urlOf('1.6.2')]

    where:
    reversed << [false, true]
  }

  def 'Reports each split row with the project url of its own version'() {
    given: 'two projects declare one version and resolve it to different versions'
    def statuses = [
      status('2.0', '3.1', urlOf('3.1'), ':app'),
      status('2.0', '3.0', urlOf('3.0'), ':lib'),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    result.outdated.dependencies.collectEntries { [it.available.milestone, it.projectUrl] } ==
      ['3.0': urlOf('3.0'), '3.1': urlOf('3.1')]

    where:
    reversed << [false, true]
  }

  def 'Reports each declared version with the project url of its own latest version'() {
    given: 'two projects declare different versions that resolve to different versions'
    def statuses = [
      status('1.0', '3.1', urlOf('3.1'), ':app'),
      status('2.0', '3.0', urlOf('3.0'), ':lib'),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    result.outdated.dependencies.collectEntries { [it.version, it.projectUrl] } ==
      ['1.0': urlOf('3.1'), '2.0': urlOf('3.0')]

    where:
    reversed << [false, true]
  }

  def 'Reports no project url where the POM of the latest version has none'() {
    given: 'the later version is published without a url'
    def statuses = [
      status('1.4.3', '1.6.2', missing),
      status('1.4.3', '1.4.3', urlOf('1.4.3')),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    def outdated = result.outdated.dependencies
    outdated*.available*.milestone == ['1.6.2']
    outdated*.projectUrl == [null]

    where:
    [missing, reversed] << [[null, ''], [false, true]].combinations()
  }

  def 'Reports each up-to-date and exceeded row with the project url of its own latest version'() {
    given: 'another declared version resolves to a later version'
    def statuses = [
      status('1.0', '3.1', urlOf('3.1'), ':app'),
      status('2.0', '2.0', urlOf('2.0'), ':lib'),
      status('3.0', '2.5', urlOf('2.5'), ':core'),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    result.current.dependencies*.projectUrl == [urlOf('2.0')]
    result.exceeded.dependencies*.projectUrl == [urlOf('2.5')]

    where:
    reversed << [false, true]
  }

  def 'Leaves the project url of a rejected version off the resolved row'() {
    given: 'the rules reject every candidate for one project, keeping the url of the rejected one'
    def rejected = new UnresolvedInfo('androidx.compose.ui', 'ui', '2.0', 'Rejected', '1.4.3')
    def statuses = [
      status('1.4.3', '1.6.2', null, ':app'),
      status('1.4.3', 'none', urlOf('2.0'), ':lib', rejected),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    result.outdated.dependencies*.projectUrl == [null]
    result.unresolved.dependencies*.projectUrl == [urlOf('2.0')]

    where:
    reversed << [false, true]
  }

  def 'Reports one project url for a version whose statuses carry different urls, whatever their order'() {
    given: 'two repositories serve the same version with different metadata'
    def statuses = [
      status('1.6.2', '1.6.2', 'https://a.example'),
      status('1.6.2', '1.6.2', 'https://b.example'),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    result.current.dependencies*.projectUrl == ['https://b.example']

    where:
    reversed << [false, true]
  }

  def 'Reports the project url of a version where only some of its statuses have one'() {
    given: 'one repository serves the version without a url'
    def statuses = [
      status('1.6.2', '1.6.2', null),
      status('1.6.2', '1.6.2', 'https://a.example'),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    result.current.dependencies*.projectUrl == ['https://a.example']

    where:
    reversed << [false, true]
  }

  def 'Reports a row moved by the report rules with the project url of its version, and its module url for a failed row'() {
    given: 'one project resolves 1.7.0, resolving it fails in another, and the rules reject 1.7.0'
    def failed = new UnresolvedInfo('androidx.compose.ui', 'ui', '1.4.3', 'Could not resolve', '1.4.3')
    def statuses = [
      status('1.4.3', '1.7.0', urlOf('1.7.0'), ':app'),
      status('1.4.3', 'none', null, ':lib', failed),
    ]
    def rejecting = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection ->
          if (selection.candidate.version == '1.7.0') {
            selection.reject('rejected')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>
    def rules = new ReportRules(rejecting, Logging.getLogger(ProjectUrlSpec), 'milestone', false, null, null)
    def candidates = [':app': ['androidx.compose.ui:ui:1.7.0', 'androidx.compose.ui:ui:1.6.2']]

    when:
    def result = reportOf(rules.applyTo(reversed ? statuses.reverse() : statuses, candidates))

    then:
    result.outdated.dependencies*.available*.milestone == ['1.6.2']
    result.outdated.dependencies*.projectUrl == [urlOf('1.6.2')]
    result.unresolved.dependencies*.projectUrl == [urlOf('1.6.2')]

    where:
    reversed << [false, true]
  }

  def 'Reports a row moved by the report rules with the project url read for its version in another list of rows'() {
    given: 'a moved row with a url that cannot be rewritten, and a row in the other list at the version it moves to'
    def stale = 'https://e.com/client-5.7.x/5.7-alpha1/'
    def real = 'https://e.com/client-5.6.x/5.6.2/'
    def moved = status('5.0', '5.7-alpha1', stale, ':app')
    def resolved = status('5.0', '5.6.2', real, ':app')
    def rejecting = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection ->
          if (selection.candidate.version == '5.7-alpha1') {
            selection.reject('rejected')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>
    def rules = new ReportRules(rejecting, Logging.getLogger(ProjectUrlSpec), 'milestone', false, null, null)
    def candidates = [':app': ['androidx.compose.ui:ui:5.7-alpha1', 'androidx.compose.ui:ui:5.6.2']]
    def urls = ReportRulesKt.urlByVersion([moved, resolved])
    def lists = reversed ? [[resolved], [moved]] : [[moved], [resolved]]

    when:
    def result = reportOf(lists.collectMany { rules.applyTo(it, candidates, urls) })

    then:
    result.outdated.dependencies*.available*.milestone == ['5.6.2']
    result.outdated.dependencies*.projectUrl == [real]

    where:
    reversed << [false, true]
  }

  def 'Reports an unresolved row with the project url of the latest version of its module'() {
    given: 'the rules reject every candidate for one project'
    def rejected = new UnresolvedInfo('androidx.compose.ui', 'ui', '2.0', 'Rejected', '1.4.3')
    def statuses = [
      status('1.4.3', '1.6.2', urlOf('1.6.2'), ':app'),
      status('1.4.3', 'none', urlOf('2.0'), ':lib', rejected),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    result.unresolved.dependencies*.projectUrl == [urlOf('1.6.2')]

    where:
    reversed << [false, true]
  }

  def 'Reports the project url of the module from a reporter built through the public constructor'() {
    given: 'two declared versions that resolve to different versions'
    def statuses = [
      status('1.0', '3.1', urlOf('3.1'), ':app'),
      status('2.0', '3.0', urlOf('3.0'), ':lib'),
    ]
    def captured = null
    def outputFormatter = [execute: { result -> captured = result }] as Action<Result>
    def built = reporterOf(reversed ? statuses.reverse() : statuses, outputFormatter)

    when:
    new DependencyUpdatesReporter(built.projectPath, built.logger, built.revision,
      new OutputFormatterArgument.CustomAction(outputFormatter), built.outputDirectory, built.reportfileName,
      built.currentVersions, built.latestVersions, built.upToDateVersions, built.downgradeVersions,
      built.upgradeVersions, built.undeclared, built.unresolved, built.projectUrls, built.gradleUpdateChecker,
      built.gradleReleaseChannel, built.latestByCurrent, built.projectsByCoordinate, built.contributedCoordinates,
      built.configurationsByCoordinate, built.skipped, built.platformProjectsByCoordinate,
      built.constrainedByCoordinate, built.preReleaseByCurrent).write()

    then:
    captured.outdated.dependencies*.projectUrl == [urlOf('3.1'), urlOf('3.1')]

    where:
    reversed << [false, true]
  }
}
