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
 * A specification for the conditions under which a coordinate the aggregated projects resolved
 * differently is split, exercised against the reporter rather than through a build.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1032')
final class SplitDivergentRowsSpec extends Specification {
  private static PartialStatus status(String projectPath, String latestVersion,
      List<String> constrainedBy = [], UnresolvedInfo unresolved = null) {
    return new PartialStatus('com.google.inject', 'guice', '2.0', null, latestVersion, null,
      unresolved, false, [], projectPath, [], constrainedBy)
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

  def 'Leaves a version one project resolved two ways merged'() {
    given: 'the root resolves the module twice, as its buildscript and its dependencies do'
    def statuses = [
      status(':', '3.1'),
      status(':', '3.0'),
      status(':app', '2.2'),
    ]

    when:
    def result = reportOf(statuses)

    then: 'splitting would leave two entries naming the root, so nothing is split'
    result.outdated.dependencies.size() == 1
    result.outdated.dependencies.first().available.milestone == '3.1'
    result.outdated.dependencies.first().projects == null
  }

  def 'Splits a version each project resolved one way'() {
    given:
    def statuses = [
      status(':', '3.1'),
      status(':app', '3.0'),
    ]

    when:
    def result = reportOf(statuses)

    then:
    result.outdated.dependencies.size() == 2
    result.outdated.dependencies*.available.milestone.sort() == ['3.0', '3.1']
    result.outdated.dependencies*.projects.flatten().sort() == [':', ':app']
  }

  def 'Leaves an unresolved row out of the split so it keeps its platform mark'() {
    given: 'a third project fails to resolve the module a platform constrains for it'
    def statuses = [
      status(':', '3.1'),
      status(':app', '3.0'),
      status(':lib', 'none', ['com.example:bom'],
        new UnresolvedInfo('com.google.inject', 'guice', '2.0', 'Could not resolve', '2.0')),
    ]

    when:
    def result = reportOf(statuses)

    then: 'the resolved rows split, and the unresolved row is still found by its own coordinate'
    result.outdated.dependencies.size() == 2
    result.unresolved.dependencies.size() == 1
    result.unresolved.dependencies.first().constrainedBy == ['com.example:bom']
  }
}
