package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.*
import groovy.transform.TupleConstructor

/**
 * A base result object reporter for the dependency updates results.
 *
 * @author Zenedith (zenedith@wp.pl)
 */
@TupleConstructor(includeFields = true)
abstract class ObjectReporter {
  /** The project evaluated against. */
  def project
  /** The revision strategy evaluated with. */
  def revision

  /** The current versions of each dependency declared in the project(s). */
  def currentVersions
  /** The latest versions of each dependency (as scoped by the revision level). */
  def latestVersions

  /** The dependencies that are up to date (same as latest found). */
  def upToDateVersions
  /** The dependencies that exceed the latest found (e.g. may not want SNAPSHOTs). */
  def downgradeVersions
  /** The dependencies where upgrades were found (below latest found). */
  def upgradeVersions
  /** The dependencies that could not be resolved. */
  def unresolved

  def buildBaseObject() {
    def current = buildCurrentGroup()
    def outdated = buildOutdatedGroup()
    def exceeded = buildExceededGroup()
    def unresolved = buildUnresolvedGroup();

    def count = current.size() + outdated.size() + exceeded.size() + unresolved.size();

    buildObject(
        count,
        buildDependenciesGroup(current),
        buildDependenciesGroup(outdated),
        buildDependenciesGroup(exceeded),
        buildDependenciesGroup(unresolved)
    )
  }

  protected def eachCurrentDep(dependencies, dep) {
    dependencies.add(buildDependency(dep.key['name'], dep.key['group'], dep.value))
  }

  protected def eachOutdatedDep(dependencies, dep) {
    dependencies.add(buildOutdatedDependency(dep.key['name'], dep.key['group'], dep.value, currentVersions[dep.key]))
  }

  protected def eachExceededDep(dependencies, dep) {
    dependencies.add(buildExceededDependency(dep.key['name'], dep.key['group'], currentVersions[dep.key], dep.value))
  }

  protected def eachUnresolvedDep(dependencies, dep) {
    def message = dep.problem.getMessage()
    def split = message.split('Required by');

    if (split.length > 0) {
      message = split[0].trim();
    }

    dependencies.add(buildUnresolvedDependency(dep.selector['name'], dep.selector['group'], currentVersions[keyOf(dep.selector)], message))
  }

  protected def buildCurrentGroup() {
    def dependencies = [];

    if (!upToDateVersions.isEmpty()) {
      upToDateVersions
          .sort { a, b -> compareKeys(a.key, b.key) }
          .each { eachCurrentDep(dependencies, it) }
    }

    return dependencies
  }

  protected def buildOutdatedGroup() {
    def dependencies = [];

    if (!upgradeVersions.isEmpty()) {
      upgradeVersions
          .sort { a, b -> compareKeys(a.key, b.key) }
          .each { eachOutdatedDep(dependencies, it) }
    }

    return dependencies
  }

  protected def buildExceededGroup() {
    def dependencies = [];

    if (!downgradeVersions.isEmpty()) {
      downgradeVersions
          .sort { a, b -> compareKeys(a.key, b.key) }
          .each { eachExceededDep(dependencies, it) }
    }

    return dependencies
  }

  protected def buildUnresolvedGroup() {
    def dependencies = [];

    if (!unresolved.isEmpty()) {
      unresolved
          .sort { a, b -> compareKeys(keyOf(a.selector), keyOf(b.selector)) }
          .each { eachUnresolvedDep(dependencies, it) }
    }

    return dependencies
  }

  protected Result buildObject(count, current, outdated, exceeded, unresolved) {
    new Result(count, current, outdated, exceeded, unresolved)
  }

  protected def buildDependenciesGroup(dependencies) {
    new DependenciesGroup(dependencies.size(), dependencies)
  }

  protected def buildDependency(name, group, version) {
    new Dependency(name, group, version)
  }

  protected def buildExceededDependency(name, group, version, latestVersion) {
    new DependencyLatest(name, group, version, latestVersion)
  }

  protected def buildUnresolvedDependency(name, group, version, reason) {
    new DependencyUnresolved(name, group, version, reason)
  }

  protected def buildOutdatedDependency(name, group, version, laterVersion) {
    def available

    switch (revision) {
      case 'milestone':
        available = new VersionAvailable(null, laterVersion)
        break;
      case 'integration':
        available = new VersionAvailable(null, null, laterVersion)
        break;
      default:
        available = new VersionAvailable(laterVersion)
    }

    new DependencyOutdated(name, group, version, available)
  }

/** Compares the dependency keys. */
  protected def compareKeys(a, b) {
    (a['group'] == b['group']) ? a['name'] <=> b['name'] : a['group'] <=> b['group']
  }

  def static keyOf(dependency) { [group: dependency.group, name: dependency.name] }

}
