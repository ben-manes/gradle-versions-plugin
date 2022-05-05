package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.Result

/**
 * An interface for reporters.
 */
interface Reporter {
  /**
   * Writes the result to the output target
   *
   * @param printStream The target, usually a [Appendable]
   * @param result the result of the dependency update analysis
   * @see Result
   */
  fun write(printStream: Appendable, result: Result)

  fun getFileExtension(): String
}
