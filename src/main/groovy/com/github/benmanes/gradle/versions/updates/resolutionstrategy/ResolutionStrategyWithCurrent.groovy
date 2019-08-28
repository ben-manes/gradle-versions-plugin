package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import com.github.benmanes.gradle.versions.updates.Coordinate
import groovy.transform.TupleConstructor
import org.gradle.api.Action
import org.gradle.api.artifacts.DependencyResolveDetails
import org.gradle.api.artifacts.DependencySubstitutions
import org.gradle.api.artifacts.ModuleVersionSelector
import org.gradle.api.artifacts.ResolutionStrategy
import org.gradle.api.artifacts.ResolutionStrategy.SortOrder

@TupleConstructor(includeFields = true)
class ResolutionStrategyWithCurrent {

  private ResolutionStrategy delegate
  private Map<Coordinate.Key, Coordinate> currentCoordinates

  ResolutionStrategyWithCurrent failOnVersionConflict() {
    delegate.failOnVersionConflict()
    return this
  }

  void preferProjectModules() {
    delegate.preferProjectModules()
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

  Set<ModuleVersionSelector> getForcedModules() {
    return delegate.getForcedModules()
  }

  ResolutionStrategyWithCurrent eachDependency(Action<? super DependencyResolveDetails> rule) {
    delegate.eachDependency(rule)
    return this
  }

  void cacheDynamicVersionsFor(int value, String units) {
    delegate.cacheDynamicVersionsFor(value, units)
  }

  void cacheDynamicVersionsFor(int value, TimeUnit units) {
    delegate.cacheDynamicVersionsFor(value, units)
  }

  void cacheChangingModulesFor(int value, String units) {
    delegate.cacheChangingModulesFor(value, units)
  }

  void cacheChangingModulesFor(int value, TimeUnit units) {
    delegate.cacheChangingModulesFor(value, units)
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
      @java.lang.Override
      void execute(ComponentSelectionRulesWithCurrent componentSelectionRulesWithCurrent) {
        closure.delegate = componentSelectionRulesWithCurrent
        closure(componentSelectionRulesWithCurrent)
      }
    })
  }

  DependencySubstitutions getDependencySubstitution() {
    return delegate.getDependencySubstitution()
  }

  ResolutionStrategyWithCurrent dependencySubstitution(
      Action<? super DependencySubstitutions> action) {
    delegate.dependencySubstitution(action)
    return this
  }

  void sortArtifacts(SortOrder sortOrder) {
    delegate.sortArtifacts(sortOrder)
  }
}
