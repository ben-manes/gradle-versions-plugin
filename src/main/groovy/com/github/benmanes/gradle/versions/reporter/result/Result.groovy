package com.github.benmanes.gradle.versions.reporter.result

import groovy.transform.TupleConstructor

/**
 * The result of a dependency update analysis
 */
@TupleConstructor
class Result {
	/**
	 * the overall number of dependencies in the project
	 */
	int count

	/**
	 * The up-to-date dependencies
	 */
	DependenciesGroup current
	/**
	 * The dependencies that can be updated
	 */
	DependenciesGroup outdated
	/**
	 * The dependencies whose versions are newer than the ones that are available from the repositories
	 */
	DependenciesGroup exceeded
	/**
	 * The unresolvable dependencies
	 */
	DependenciesGroup unresolved
}
