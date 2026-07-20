package com.github.benmanes.gradle.versions

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE

import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.DependencyUpdates
import com.github.benmanes.gradle.versions.updates.OutputFormatterArgument
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for a module that a resolution rule substitutes with one of a different group or
 * name. https://github.com/ben-manes/gradle-versions-plugin/issues/990
 */
final class DependencySubstitutionSpec extends Specification {

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/990')
  def 'A substituted module is reported against the latest version of what it resolves to'() {
    given:
    def project = substituting('com.google.guava:guava:15.0')

    when:
    def result = evaluate(project, null)

    then:
    result.current.dependencies.isEmpty()
    with(result.outdated.dependencies.toList()) {
      it*.name == ['guava']
      it*.version == ['15.0']
      it*.available*.milestone == ['16.0-rc1']
    }
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/990')
  def 'A component selection rule sees the substituted version as the current one'() {
    given:
    def project = substituting('com.google.guava:guava:15.0')
    def seen = []

    when:
    evaluate(project, { selection ->
      seen += "$selection.candidate.module:$selection.currentVersion -> $selection.candidate.version"
    })

    then:
    seen == ['guava:15.0 -> 16.0-rc1']
  }

  /** A project declaring a module that a substitution rule replaces with {@code replacement}. */
  private def substituting(String replacement) {
    def project = ProjectBuilder.builder().withName('root').build()
    project.repositories {
      maven {
        url getClass().getResource('/maven/').toURI()
      }
    }
    project.configurations {
      app
    }
    project.dependencies {
      app 'com.thoughtworks.xstream:xstream:1.4.17'
    }
    project.configurations.app.resolutionStrategy.dependencySubstitution {
      substitute module('com.thoughtworks.xstream:xstream') using module(replacement)
    }
    return project
  }

  private static Result evaluate(project, Closure selectionRule) {
    Result captured = null
    def strategy = selectionRule == null ? null : { ResolutionStrategyWithCurrent it ->
      it.componentSelection { rules -> rules.all(selectionRule) }
    } as Action<ResolutionStrategyWithCurrent>
    new DependencyUpdates(project, strategy, 'milestone',
      new OutputFormatterArgument.CustomAction({ result -> captured = result } as Action<Result>),
      'build', 'report', false, 'https://services.gradle.org/versions/', RELEASE_CANDIDATE.id)
      .run().write()
    return captured
  }
}
