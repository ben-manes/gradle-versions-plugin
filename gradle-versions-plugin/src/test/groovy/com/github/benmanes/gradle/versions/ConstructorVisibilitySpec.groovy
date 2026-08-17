package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.Coordinate
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesReporter
import com.github.benmanes.gradle.versions.updates.PartialResult
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent
import kotlin.jvm.JvmClassMappingKt
import kotlin.reflect.KVisibility
import spock.lang.Issue
import spock.lang.Specification

/**
 * A constructor a release ships is published API forever, so an arity only this module calls must
 * not reach a tag as public.
 */
final class ConstructorVisibilitySpec extends Specification {
  def 'The selection wrapper has no three-argument constructor'() {
    expect:
    ComponentSelectionWithCurrent.declaredConstructors.every { it.parameterCount != 3 }
  }

  def 'The selection wrapper constructs internally, except at the arity v0.59.0 shipped'() {
    given:
    def constructors = JvmClassMappingKt.getKotlinClass(ComponentSelectionWithCurrent).constructors
    def released = constructors.find { it.parameters.size() == 2 }
    def constraintCarrying = constructors.findAll { it.parameters.size() > 2 }

    expect:
    released.visibility == KVisibility.PUBLIC
    constraintCarrying.every { it.visibility == KVisibility.INTERNAL }
  }

  def 'The constraint-carrying Coordinate constructors are internal'() {
    given:
    def constraintCarrying = JvmClassMappingKt.getKotlinClass(Coordinate).constructors
      .findAll { it.parameters.size() > 4 }

    expect:
    constraintCarrying.size() == 4
    constraintCarrying.every { it.visibility == KVisibility.INTERNAL }
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/801')
  def 'The arities gaining the skipped configurations widened are still callable'() {
    expect:
    Result.declaredConstructors.any { it.parameterCount == 7 }
    DependencyUpdatesReporter.declaredConstructors.any { it.parameterCount == 20 }
    PartialResult.declaredMethods.any { it.name == 'copy' && it.parameterCount == 4 }
  }
}
