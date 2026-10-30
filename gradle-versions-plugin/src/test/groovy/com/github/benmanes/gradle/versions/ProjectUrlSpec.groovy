package com.github.benmanes.gradle.versions

import static com.github.benmanes.gradle.versions.updates.DependencyUpdatesReporterKt.reporterFor
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE

import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.OutputFormatterArgument
import com.github.benmanes.gradle.versions.updates.PartialStatus
import com.github.benmanes.gradle.versions.updates.UnresolvedInfo
import org.gradle.api.Action
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

  private static Result reportOf(List<PartialStatus> statuses) {
    def project = ProjectBuilder.builder().build()
    def captured = null
    def outputFormatter = [execute: { result -> captured = result }] as Action<Result>
    reporterFor(statuses, project.path, project.logger, 'milestone',
      new OutputFormatterArgument.CustomAction(outputFormatter), project.file('build'), 'report',
      false, 'https://services.gradle.org/versions/', RELEASE_CANDIDATE.id).write()
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
      status('1.4.3', '1.6.2', null),
      status('1.4.3', '1.4.3', urlOf('1.4.3')),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    def outdated = result.outdated.dependencies
    outdated*.available*.milestone == ['1.6.2']
    outdated*.projectUrl == [null]

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
}
