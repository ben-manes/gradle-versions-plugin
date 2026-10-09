package com.github.benmanes.gradle.versions.reporter.result

/**
 * A group of dependencies.
 *
 * @param count Ignored. It is kept for callers of the constructor that took a stored count.
 * @property count The number of dependencies in this group, read from [dependencies] so that it
 * stays correct after a custom output formatter adds or removes one.
 * @property dependencies The dependencies that belong to this group.
 */
class DependenciesGroup<T : Dependency>(
  // Moshi writes a property without a backing field to the JSON report only when the primary
  // constructor has a parameter of the same name, so this parameter has to stay.
  @Suppress("UNUSED_PARAMETER") count: Int,
  val dependencies: MutableSet<T> = mutableSetOf(),
) {
  val count: Int
    get() = dependencies.size

  // Not a data class, which would have to store the count. Its members are written out instead so
  // that callers compiled against the data class still link.
  operator fun component1(): Int = count

  operator fun component2(): MutableSet<T> = dependencies

  fun copy(
    count: Int = this.count,
    dependencies: MutableSet<T> = this.dependencies,
  ): DependenciesGroup<T> = DependenciesGroup(count, dependencies)

  override fun equals(other: Any?): Boolean = other is DependenciesGroup<*> && dependencies == other.dependencies

  override fun hashCode(): Int = dependencies.hashCode()

  override fun toString(): String = "DependenciesGroup(count=$count, dependencies=$dependencies)"
}
