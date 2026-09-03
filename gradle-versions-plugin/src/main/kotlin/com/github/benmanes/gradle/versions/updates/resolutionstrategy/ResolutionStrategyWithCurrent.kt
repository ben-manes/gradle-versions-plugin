package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import com.github.benmanes.gradle.versions.updates.Coordinate
import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.artifacts.DependencyResolveDetails
import org.gradle.api.artifacts.DependencySubstitutions
import org.gradle.api.artifacts.ResolutionStrategy

class ResolutionStrategyWithCurrent internal constructor(
  private val delegate: ResolutionStrategy,
  private val currentCoordinates: Map<Coordinate.Key, Coordinate>,
  private val onDeprecatedBoundRead: () -> Unit,
) {
  /** Retained so the arity released before the deprecation warning was added still links. */
  constructor(
    delegate: ResolutionStrategy,
    currentCoordinates: Map<Coordinate.Key, Coordinate>,
  ) : this(delegate, currentCoordinates, {})

  fun failOnVersionConflict(): ResolutionStrategyWithCurrent {
    delegate.failOnVersionConflict()
    return this
  }

  fun activateDependencyLocking(): ResolutionStrategyWithCurrent {
    delegate.activateDependencyLocking()
    return this
  }

  fun force(vararg moduleVersionSelectorNotations: Any?): ResolutionStrategyWithCurrent {
    delegate.force(moduleVersionSelectorNotations)
    return this
  }

  fun setForcedModules(vararg moduleVersionSelectorNotations: Any?): ResolutionStrategyWithCurrent {
    delegate.setForcedModules(moduleVersionSelectorNotations)
    return this
  }

  fun eachDependency(rule: Action<in DependencyResolveDetails>): ResolutionStrategyWithCurrent {
    delegate.eachDependency(rule)
    return this
  }

  fun dependencySubstitution(action: Action<in DependencySubstitutions>): ResolutionStrategyWithCurrent {
    delegate.dependencySubstitution(action)
    return this
  }

  fun componentSelection(action: Action<in ComponentSelectionRulesWithCurrent>): ResolutionStrategyWithCurrent {
    action.execute(getComponentSelectionNonDelegate())
    return this
  }

  fun componentSelection(closure: Closure<*>): ResolutionStrategyWithCurrent {
    return componentSelection {
      closure.delegate = it
      closure.call(it)
    }
  }

  private fun getComponentSelectionNonDelegate(): ComponentSelectionRulesWithCurrent {
    return ComponentSelectionRulesWithCurrent(
      delegate.componentSelection,
      currentCoordinates,
      onDeprecatedBoundRead,
    )
  }
}
