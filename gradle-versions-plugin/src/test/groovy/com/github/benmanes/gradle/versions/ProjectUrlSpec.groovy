package com.github.benmanes.gradle.versions

import static com.github.benmanes.gradle.versions.updates.DependencyUpdatesReporterKt.reporterFor
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE

import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.OutputFormatterArgument
import com.github.benmanes.gradle.versions.updates.PartialStatus
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

  private static PartialStatus status(String declaredVersion, String latestVersion, String projectUrl) {
    return new PartialStatus('androidx.compose.ui', 'ui', declaredVersion, null, latestVersion,
      projectUrl, null)
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
      status('1.4.3', '1.6.2', "${PROJECT_URL}#1.6.2"),
      status('1.4.3', '1.4.3', "${PROJECT_URL}#1.4.3"),
    ]

    when:
    def result = reportOf(reversed ? statuses.reverse() : statuses)

    then:
    def outdated = result.outdated.dependencies
    outdated*.available*.milestone == ['1.6.2']
    outdated*.projectUrl == ["${PROJECT_URL}#1.6.2".toString()]

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
    def inOrder = reportOf(statuses).current.dependencies*.projectUrl
    def reversed = reportOf(statuses.reverse()).current.dependencies*.projectUrl

    then:
    inOrder.size() == 1
    inOrder == reversed
  }
}
