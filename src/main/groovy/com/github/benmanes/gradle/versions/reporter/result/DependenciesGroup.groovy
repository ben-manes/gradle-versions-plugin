package com.github.benmanes.gradle.versions.reporter.result

import groovy.transform.TupleConstructor

/**
 * A group of dependencies
 *
 */
@TupleConstructor
class DependenciesGroup {

	/**
	 * The number of dependencies in this group
	 */
	int count

	/**
	 * The dependencies that belong to this group
	 */
	List<Dependency> dependencies = []
}
