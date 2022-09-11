package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.reporter.Reporter
import com.github.benmanes.gradle.versions.reporter.result.Result
import org.gradle.api.Action

/**
 * Represents all the types of arguments for output formatting supported in [DependencyUpdatesTask].
 */
sealed interface OutputFormatterArgument {

  /**
   * A string representing one of the built-in output formatters (i.e. "json", "text", "html" or
   * "xml"), or a comma-separated list with a combination of them (e.g. "json,text").
   */
  class BuiltIn(val formatterNames: String) : OutputFormatterArgument

  /**
   * An implementation of the [Reporter] interface to provide a custom output formatting.
   */
  class CustomReporter(val reporter: Reporter) : OutputFormatterArgument

  /**
   * An [Action] to provide a custom output formatting. Enables the use of the trailing closure/lambda
   * syntax for output formatting definition.
   */
  class CustomAction(val action: Action<Result>) : OutputFormatterArgument

  companion object {
    @JvmField
    val DEFAULT = BuiltIn(formatterNames = "text")
  }
}
