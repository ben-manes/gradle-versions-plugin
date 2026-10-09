package com.github.benmanes.gradle.versions.reporter.result

import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResults

/**
 * The result of a dependency update analysis.
 *
 * @param count Ignored. It is kept for callers of the constructors that took a stored count.
 * @property count The number of dependencies reported, counting one that appears in more than one
 * section once for each of them. It is the sum of the five groups' counts, so it stays correct
 * after a custom output formatter adds or removes a dependency.
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
    // Moshi writes a property without a backing field to the JSON report only when the primary
    // constructor has a parameter of the same name, so this parameter has to stay.
    @Suppress("UNUSED_PARAMETER") count: Int,
    val current: DependenciesGroup<Dependency>,
    val outdated: DependenciesGroup<DependencyOutdated>,
    val exceeded: DependenciesGroup<DependencyLatest>,
    val undeclared: DependenciesGroup<Dependency>,
    val unresolved: DependenciesGroup<DependencyUnresolved>,
    val gradle: GradleUpdateResults,
    val skipped: SkippedConfigurationsGroup = SkippedConfigurationsGroup(0),
  ) {
    val count: Int
      get() = current.count + outdated.count + exceeded.count + undeclared.count + unresolved.count

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
