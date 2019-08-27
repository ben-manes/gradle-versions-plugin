package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import com.github.benmanes.gradle.versions.updates.Coordinate
import org.gradle.api.Action
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.ComponentSelectionRules
import org.gradle.internal.rules.RuleAction
import org.gradle.internal.rules.RuleSourceBackedRuleAction
import org.gradle.model.internal.type.ModelType

class ComponentSelectionRulesWithCurrent {

  private ComponentSelectionRules delegate
  private Map<Coordinate.Key, Coordinate> currentCoordinates

  ComponentSelectionRulesWithCurrent(ComponentSelectionRules delegate, Map<Coordinate.Key, Coordinate> currentCoordinates) {
    this.delegate = delegate
    this.currentCoordinates = currentCoordinates
  }

  ComponentSelectionRulesWithCurrent all(Action<? super ComponentSelectionWithCurrent> selectionAction) {
    delegate.all(new Action<ComponentSelection>() {
      void execute(ComponentSelection inner) {
        selectionAction.execute(wrapComponentSelection(inner))
      }
    })
    return this
  }

  ComponentSelectionRulesWithCurrent all(Closure<?> closure) {
    delegate.all(new Action<ComponentSelection>() {
      void execute(ComponentSelection inner) {
        ComponentSelectionWithCurrent wrapped = wrapComponentSelection(inner)
        closure.delegate = wrapped
        closure(wrapped)
      }
    })
    return this
  }

  ComponentSelectionRulesWithCurrent all(Object ruleSource) {
    RuleAction<ComponentSelectionWithCurrent> ruleAction = RuleSourceBackedRuleAction.create(ModelType.of(ComponentSelectionWithCurrent.class), ruleSource)
    delegate.all(new Action<ComponentSelection>() {
      void execute(ComponentSelection inner) {
        ruleAction.execute(wrapComponentSelection(inner), [])
      }
    })
    return this
  }

  ComponentSelectionRulesWithCurrent withModule(Object id, Action<? super ComponentSelectionWithCurrent> selectionAction) {
    delegate.withModule(id, selectionAction)
    return this
  }

  ComponentSelectionRulesWithCurrent withModule(Object id, Closure<?> closure) {
    delegate.withModule(id, closure)
    return this
  }

  ComponentSelectionRulesWithCurrent withModule(Object id, Object ruleSource) {
    delegate.withModule(id, ruleSource)
    return this
  }

  private ComponentSelectionWithCurrent wrapComponentSelection(ComponentSelection inner) {
    Coordinate candidateCoordinate = Coordinate.from(inner.candidate)
    Coordinate current = currentCoordinates.get(candidateCoordinate.key)
    ComponentSelectionWithCurrent wrapped = new ComponentSelectionWithCurrent(inner, current.version)
    return wrapped
  }
}
