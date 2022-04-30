package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import com.github.benmanes.gradle.versions.updates.Coordinate
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor
import org.gradle.api.Action
import org.gradle.api.artifacts.DependencyResolveDetails
import org.gradle.api.artifacts.DependencySubstitutions
import org.gradle.api.artifacts.ResolutionStrategy

@CompileStatic
@TupleConstructor(includeFields = true)
class ResolutionStrategyWithCurrent {

  @Delegate(interfaces = false, excludes = ["componentSelection", "getComponentSelection"])
  private ResolutionStrategy delegate

  private Map<Coordinate.Key, Coordinate> currentCoordinates

  ResolutionStrategyWithCurrent failOnVersionConflict() {
    delegate.failOnVersionConflict()
    return this
  }

  ResolutionStrategyWithCurrent activateDependencyLocking() {
    delegate.activateDependencyLocking()
    return this
  }

  ResolutionStrategyWithCurrent force(Object... moduleVersionSelectorNotations) {
    delegate.force(moduleVersionSelectorNotations)
    return this
  }

  ResolutionStrategyWithCurrent setForcedModules(Object... moduleVersionSelectorNotations) {
    delegate.setForcedModules(moduleVersionSelectorNotations)
    return this
  }

  ResolutionStrategyWithCurrent eachDependency(Action<? super DependencyResolveDetails> rule) {
    delegate.eachDependency(rule)
    return this
  }

  ComponentSelectionRulesWithCurrent getComponentSelection() {
    return new ComponentSelectionRulesWithCurrent(delegate.getComponentSelection(),
      currentCoordinates)
  }

  ResolutionStrategyWithCurrent componentSelection(
      Action<? super ComponentSelectionRulesWithCurrent> action) {
    action.execute(getComponentSelection())
    return this
  }

  ResolutionStrategyWithCurrent componentSelection(Closure<?> closure) {
    return componentSelection(new Action<ComponentSelectionRulesWithCurrent>() {
      @Override
      void execute(ComponentSelectionRulesWithCurrent componentSelectionRulesWithCurrent) {
        closure.delegate = componentSelectionRulesWithCurrent
        closure(componentSelectionRulesWithCurrent)
      }
    })
  }

  ResolutionStrategyWithCurrent dependencySubstitution(
      Action<? super DependencySubstitutions> action) {
    delegate.dependencySubstitution(action)
    return this
  }
}
