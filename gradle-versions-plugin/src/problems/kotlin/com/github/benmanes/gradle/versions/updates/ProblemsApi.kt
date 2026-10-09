package com.github.benmanes.gradle.versions.updates

import org.gradle.api.problems.ProblemGroup
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.Problems
import javax.inject.Inject

/** Reports a finding to Gradle's Problems API, which is available from Gradle 8.13. */
internal abstract class ProblemsApi {
  @get:Inject
  abstract val problems: Problems

  /**
   * Reports one finding. Gradle keeps only the first 15 problems of an id, so [name] is one that few
   * findings share, such as the module of an outdated dependency. The severity is left at Gradle's
   * default, which is a warning.
   */
  fun report(
    name: String,
    displayName: String,
    label: String,
    details: String?,
    solutions: List<String> = emptyList(),
  ) {
    problems.reporter.report(ProblemId.create(name, displayName, GROUP)) { spec ->
      spec.contextualLabel(label)
      details?.let { spec.details(it) }
      solutions.forEach { spec.solution(it) }
    }
  }

  private companion object {
    val GROUP: ProblemGroup = ProblemGroup.create("dependency-updates", "Dependency updates")
  }
}
