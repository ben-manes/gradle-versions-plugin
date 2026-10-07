package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.ComponentSelectionRules
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.internal.rules.RuleSourceBackedRuleAction
import org.gradle.model.internal.type.ModelType

/**
 * Harvests the actions a [ComponentSelectionRulesWithCurrent] registers, in registration order,
 * rather than running them against a live Gradle resolution. Fed to [ResolutionStrategyWithCurrent]
 * as the component-selection delegate of the internal constructor `ReportRules` uses, so the
 * build's `resolutionStrategy` action runs exactly once and every registered rule is replayable
 * afterwards.
 *
 * Only the [Action]-typed overloads ever run in practice: [ComponentSelectionRulesWithCurrent]'s
 * `Closure` and rule-source overloads already reduce to a call on the `Action` overload before
 * reaching a [ComponentSelectionRules] delegate, so the other four overloads here exist to satisfy
 * the interface and would answer correctly if ever called directly.
 */
internal class CollectingComponentSelectionRules : ComponentSelectionRules {
  private data class Rule(
    val moduleId: Any?,
    val action: Action<in ComponentSelection>,
  )

  private val rules = mutableListOf<Rule>()

  override fun all(action: Action<in ComponentSelection>): ComponentSelectionRules = register(null, action)

  override fun all(closure: Closure<*>): ComponentSelectionRules = register(null, Action { selection -> closure.call(selection) })

  override fun all(ruleSource: Any): ComponentSelectionRules = register(null, ruleSourceAction(ruleSource))

  override fun withModule(
    id: Any,
    action: Action<in ComponentSelection>,
  ): ComponentSelectionRules = register(id, action)

  override fun withModule(
    id: Any,
    closure: Closure<*>,
  ): ComponentSelectionRules = register(id, Action { selection -> closure.call(selection) })

  override fun withModule(
    id: Any,
    ruleSource: Any,
  ): ComponentSelectionRules = register(id, ruleSourceAction(ruleSource))

  /** Returns the registered rules for this module, and those with no module id, in registration order. */
  fun rulesFor(
    group: String,
    name: String,
  ): List<Action<in ComponentSelection>> = rules.filter { matches(it.moduleId, group, name) }.map { it.action }

  val isEmpty: Boolean
    get() = rules.isEmpty()

  private fun register(
    moduleId: Any?,
    action: Action<in ComponentSelection>,
  ): ComponentSelectionRules {
    rules.add(Rule(moduleId, action))
    return this
  }

  private fun ruleSourceAction(ruleSource: Any): Action<in ComponentSelection> {
    val ruleAction = RuleSourceBackedRuleAction.create(ModelType.of(ComponentSelection::class.java), ruleSource)
    return Action { selection -> ruleAction.execute(selection, mutableListOf<Any>()) }
  }

  private fun matches(
    moduleId: Any?,
    group: String,
    name: String,
  ): Boolean =
    when (moduleId) {
      null -> true
      // A CharSequence rather than a String: a Groovy script interpolating the notation passes a
      // GString, which Gradle's own notation parser accepts and an `is String` test does not.
      is CharSequence -> moduleId.toString() == "$group:$name"
      is ModuleIdentifier -> moduleId.group == group && moduleId.name == name
      else -> false
    }
}
