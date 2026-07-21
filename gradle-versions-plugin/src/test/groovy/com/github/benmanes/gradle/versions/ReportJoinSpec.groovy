package com.github.benmanes.gradle.versions

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE

import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.OutputFormatterArgument
import groovy.json.JsonSlurper
import org.gradle.api.Action
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for reporting each declared version against the latest version found for it.
 */
final class ReportJoinSpec extends Specification {

  @Issue([
    'https://github.com/ben-manes/gradle-versions-plugin/issues/348',
    'https://github.com/ben-manes/gradle-versions-plugin/issues/906',
  ])
  def 'An outdated version is reported against the latest version found for it'() {
    given:
    def root = twoProjects('com.google.inject', 'guice', '3\\.1', '2.0', '3.0')

    when:
    def result = evaluate(root)

    then:
    result.current.dependencies.isEmpty()
    with(result.outdated.dependencies.toList()) {
      it*.version == ['2.0', '3.0']
      it*.available*.milestone == ['3.0', '3.1']
    }
  }

  @Issue([
    'https://github.com/ben-manes/gradle-versions-plugin/issues/348',
    'https://github.com/ben-manes/gradle-versions-plugin/issues/906',
  ])
  def 'Multiple up to date versions of a module all survive into the report'() {
    given:
    def root = twoProjects('org.apache.logging.log4j', 'log4j-core', '2\\.17\\.0', '2.16.0', '2.17.0')

    when:
    def result = evaluate(root)

    then:
    result.current.dependencies*.version == ['2.16.0', '2.17.0']
    result.current.dependencies.every {
      it.group == 'org.apache.logging.log4j' && it.name == 'log4j-core'
    }
    result.outdated.dependencies.isEmpty()
  }

  @Issue([
    'https://github.com/ben-manes/gradle-versions-plugin/issues/348',
    'https://github.com/ben-manes/gradle-versions-plugin/issues/906',
  ])
  def 'Multiple up to date versions of a module all survive into the report file'() {
    given:
    def root = twoProjects('org.apache.logging.log4j', 'log4j-core', '2\\.17\\.0', '2.16.0', '2.17.0')

    when:
    writeReport(root, 'json')

    then:
    def report = new JsonSlurper().parse(new File(root.projectDir, 'build/report.json'))
    report.current.dependencies*.version == ['2.16.0', '2.17.0']
    report.count == 2
  }

  /**
   * A root project whose two children declare the same module, where the first child's repository
   * hides the versions matching {@code hiddenVersionRegex}. The latest version found therefore
   * differs between the children, which a report keyed on the group and name alone cannot express.
   */
  private def twoProjects(String group, String artifact, String hiddenVersionRegex,
      String pinnedVersion, String openVersion) {
    def root = ProjectBuilder.builder().withName('root').build()
    def pinned = ProjectBuilder.builder().withName('pinned').withParent(root).build()
    def open = ProjectBuilder.builder().withName('open').withParent(root).build()
    def localMavenRepo = getClass().getResource('/maven/')
    pinned.repositories {
      maven {
        url localMavenRepo.toURI()
        content {
          excludeVersionByRegex(group.replace('.', '\\.'), artifact, hiddenVersionRegex)
        }
      }
    }
    open.repositories {
      maven {
        url localMavenRepo.toURI()
      }
    }
    for (project in [pinned, open]) {
      project.configurations {
        app
      }
    }
    pinned.dependencies {
      app "$group:$artifact:$pinnedVersion"
    }
    open.dependencies {
      app "$group:$artifact:$openVersion"
    }
    return root
  }

  private static Result evaluate(project) {
    Result captured = null
    reportWith(project,
      new OutputFormatterArgument.CustomAction({ result -> captured = result } as Action<Result>))
    return captured
  }

  private static void writeReport(project, String outputFormat) {
    reportWith(project, new OutputFormatterArgument.BuiltIn(outputFormat))
  }

  private static void reportWith(project, OutputFormatterArgument formatter) {
    ProjectEvaluator.evaluate(project, null, 'milestone', formatter, 'build', 'report', false,
      'https://services.gradle.org/versions/', RELEASE_CANDIDATE.id).write()
  }
}
