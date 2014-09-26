package com.github.benmanes.gradle.versions.reporter.result

import groovy.transform.TupleConstructor

/**
 * A group of dependencies
 *
 */
@TupleConstructor
class DependenciesGroup<T extends Dependency> {

	/**
	 * The number of dependencies in this group
	 */
	int count

	/**
	 * The dependencies that belong to this group
	 */
	List<T> dependencies = []
}
