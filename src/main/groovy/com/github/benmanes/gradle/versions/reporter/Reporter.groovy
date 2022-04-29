package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.Result
import groovy.transform.CompileStatic

/**
 * An interface for reporters.
 */
@CompileStatic
interface Reporter {
  /**
   * Writes the result to the output target
   *
   * @param target The target, usually a {@link PrintStream}
   * @param result the result of the dependency update analysis
   * @see Result
   */
  void write(Appendable target, Result result)

  String getFileExtension()
}
