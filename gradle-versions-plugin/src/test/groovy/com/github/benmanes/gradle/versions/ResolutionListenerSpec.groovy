package com.github.benmanes.gradle.versions

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE

import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.DependencyUpdates
import com.github.benmanes.gradle.versions.updates.OutputFormatterArgument
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.artifacts.DependencyResolutionListener
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for a dependency that a resolution listener contributes while the first
 * configuration is being resolved.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/992
 */
final class ResolutionListenerSpec extends Specification {

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/992')
  def 'A contributed dependency is reported against its declared version'() {
    given:
    def project = contributing('com.google.guava:guava:15.0')

    when:
    def result = evaluate(project, null)

    then:
    result.current.dependencies.isEmpty()
    with(result.outdated.dependencies.toList()) {
      it*.name == ['guava', 'guice']
      it*.version == ['15.0', '2.0']
      it*.available*.milestone == ['16.0-rc1', '3.1']
    }
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/992')
  def 'A component selection rule sees a contributed dependency'() {
    given:
    def project = contributing('com.google.guava:guava:15.0')
    def seen = []

    when:
    evaluate(project, { selection ->
      seen += "$selection.candidate.module:$selection.currentVersion -> $selection.candidate.version".toString()
    })

    then:
    seen.contains('guava:15.0 -> 16.0-rc1')
  }

  /**
   * A project whose declared set gains {@code contribution} only once a resolution has begun, which
   * is how a build-scoped listener adds one. The anchor dependency keeps the declared set non-empty
   * so that the resolver does not short-circuit before the listener has fired.
   */
  private static def contributing(String contribution) {
    def project = ProjectBuilder.builder().withName('root').build()
    project.repositories {
      maven {
        url ResolutionListenerSpec.getResource('/maven/').toURI()
      }
    }
    project.configurations {
      app
    }
    project.dependencies {
      app 'com.google.inject:guice:2.0'
    }
    def contributed = false
    project.gradle.addListener(new DependencyResolutionListener() {
      @Override
      void beforeResolve(ResolvableDependencies incoming) {
        if (!contributed) {
          contributed = true
          project.dependencies.add('app', contribution)
        }
      }

      @Override
      void afterResolve(ResolvableDependencies incoming) {
      }
    })
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
