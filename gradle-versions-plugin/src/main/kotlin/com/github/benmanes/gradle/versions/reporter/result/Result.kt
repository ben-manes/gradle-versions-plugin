package com.github.benmanes.gradle.versions.reporter.result

import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResults
import com.squareup.moshi.JsonClass

/**
 * The result of a dependency update analysis.
 *
 * @property count The overall number of dependencies in the project.
 * @property current The up-to-date dependencies.
 * @property outdated The dependencies that can be updated.
 * @property exceeded The dependencies whose versions are newer than the ones that are available
 * from the repositories.
 * @property undeclared The dependencies whose versions were not declared.
 * @property unresolved The unresolvable dependencies.
 * @property gradle Gradle release channels and respective update availability.
 */
@JsonClass(generateAdapter = true)
class Result(
  val count: Int,
  val current: DependenciesGroup<Dependency>,
  val outdated: DependenciesGroup<DependencyOutdated>,
  val exceeded: DependenciesGroup<DependencyLatest>,
  val undeclared: DependenciesGroup<Dependency>,
  val unresolved: DependenciesGroup<DependencyUnresolved>,
  val gradle: GradleUpdateResults,
)
