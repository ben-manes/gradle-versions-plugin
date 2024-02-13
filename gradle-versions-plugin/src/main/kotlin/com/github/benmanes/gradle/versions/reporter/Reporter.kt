package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.Result
import java.io.OutputStream

/**
 * An interface for reporters.
 */
interface Reporter {
  /**
   * Writes the result to the output target
   *
   * @param printStream The target, usually a [OutputStream]
   * @param result the result of the dependency update analysis
   * @see Result
   */
  fun write(
    printStream: OutputStream,
    result: Result,
  )

  fun getFileExtension(): String
}
