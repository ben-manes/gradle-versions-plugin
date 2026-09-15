package com.github.benmanes.gradle.versions.updates

import org.gradle.api.problems.ProblemGroup
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.Problems
import javax.inject.Inject

/** Reports an outdated dependency to Gradle's Problems API, which is available from Gradle 8.13. */
internal abstract class ProblemsReporter {
  @get:Inject
  abstract val problems: Problems

  /**
   * Reports one outdated dependency, with the lines under its report row as details and each upgrade
   * as a solution. Each module is reported under an id of its own, as Gradle keeps only the first 15
   * problems of an id.
   */
  fun reportOutdated(
    module: String,
    label: String,
    details: String?,
    solutions: List<String>,
  ) {
    problems.reporter.report(ProblemId.create(module, "Outdated dependency", GROUP)) { spec ->
      spec.contextualLabel(label)
      details?.let { spec.details(it) }
      solutions.forEach { spec.solution(it) }
    }
  }

  private companion object {
    val GROUP: ProblemGroup = ProblemGroup.create("dependency-updates", "Dependency updates")
  }
}
