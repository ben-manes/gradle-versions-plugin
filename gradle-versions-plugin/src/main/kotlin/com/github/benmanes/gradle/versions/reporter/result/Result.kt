package com.github.benmanes.gradle.versions.reporter.result

import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResults

/**
 * The result of a dependency update analysis.
 *
 * @property count The number of dependencies reported, counting one that appears in more than one
 * section once for each of them.
 * @property current The up-to-date dependencies.
 * @property outdated The dependencies that can be updated.
 * @property exceeded The dependencies whose versions are newer than the ones that are available
 * from the repositories.
 * @property undeclared The dependencies whose versions were not declared.
 * @property unresolved The unresolvable dependencies.
 * @property gradle Gradle release channels and respective update availability.
 * @property skipped The configurations whose dependencies could not be inspected, which [count] does not include.
 * @property leftOutEmbeddedKotlin The number of entries left out because Gradle sets their versions for its
 * embedded Kotlin, which [count] does not include.
 */
class Result
  @JvmOverloads
  constructor(
    val count: Int,
    val current: DependenciesGroup<Dependency>,
    val outdated: DependenciesGroup<DependencyOutdated>,
    val exceeded: DependenciesGroup<DependencyLatest>,
    val undeclared: DependenciesGroup<Dependency>,
    val unresolved: DependenciesGroup<DependencyUnresolved>,
    val gradle: GradleUpdateResults,
    val skipped: SkippedConfigurationsGroup = SkippedConfigurationsGroup(0),
  ) {
    var leftOutEmbeddedKotlin: Int = 0
      private set

    // A secondary constructor rather than a defaulted parameter, which would replace the
    // constructor that a Kotlin caller leaving out `skipped` was compiled against.
    constructor(
      count: Int,
      current: DependenciesGroup<Dependency>,
      outdated: DependenciesGroup<DependencyOutdated>,
      exceeded: DependenciesGroup<DependencyLatest>,
      undeclared: DependenciesGroup<Dependency>,
      unresolved: DependenciesGroup<DependencyUnresolved>,
      gradle: GradleUpdateResults,
      skipped: SkippedConfigurationsGroup,
      leftOutEmbeddedKotlin: Int,
    ) : this(count, current, outdated, exceeded, undeclared, unresolved, gradle, skipped) {
      this.leftOutEmbeddedKotlin = leftOutEmbeddedKotlin
    }
  }
