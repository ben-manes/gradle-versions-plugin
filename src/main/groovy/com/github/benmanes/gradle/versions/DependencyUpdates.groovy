/*
 * Copyright 2012 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.gradle.versions

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.internal.artifacts.version.LatestVersionSemanticComparator
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * A task that reports which dependencies have newer versions.
 * <p>
 * The <tt>revision</tt> property controls the resolution strategy:
 * <ul>
 *   <li>release: selects the latest release
 *   <li>milestone: select the latest version being either a milestone or a release (default)
 *   <li>integration: selects the latest revision of the dependency module (such as SNAPSHOT)
 * </ul>
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
class DependencyUpdates extends DefaultTask {

  @Input
  String revision = 'milestone'

  @TaskAction
  def dependencyUpdates() {
    def current = getProjectAndBuildscriptDependencies()
    def (resolved, unresolved) = resolveLatestDepedencies(current)
    def (currentVersions, latestVersions, sameVersions, downgradeVersions, upgradeVersions) =
      getVersionMapping(current, resolved)
    displayReport(currentVersions, latestVersions, sameVersions,
      downgradeVersions, upgradeVersions, unresolved)
  }

  /** Returns {@link ExternalDependency} collected from the project and buildscript. */
  def getProjectAndBuildscriptDependencies() {
    project.allprojects.collectMany{ proj ->
      def configurations = (proj.configurations + proj.buildscript.configurations)
      configurations.collectMany { it.allDependencies }
    }.findAll { it instanceof ExternalDependency }
  }

  /**
   * Returns {@link ResolvedDependency} and {@link UnresolvedDependency} collected after evaluating
   * the latest dependencies to determine the newest versions.
   */
  def resolveLatestDepedencies(current) {
    if (current.empty) {
      return [[], []]
    }
    def unresolved = current.collect { dependency ->
      project.dependencies.create(group: dependency.group, name: dependency.name,
          version: "latest.${revisionLevel()}") {
        transitive = false
      }
    }
    def lenient = project.configurations.detachedConfiguration(unresolved as Dependency[])
      .resolvedConfiguration.lenientConfiguration
    [lenient.firstLevelModuleDependencies, lenient.unresolvedModuleDependencies]
  }

  /** Organizes the dependencies into version mappings. */
  def getVersionMapping(current, resolved) {
    def currentVersions = current.collectEntries { dependency ->
       [label(dependency), dependency.version]
    }
    def latestVersions = resolved.collectEntries { dependency ->
      [label(dependency.module.id), dependency.moduleVersion]
    }
    def sameVersions = currentVersions.intersect(latestVersions)

    def comparator = new LatestVersionSemanticComparator()
    def upgradeVersions = latestVersions.findAll { label, version ->
      comparator.compare(version, currentVersions[label]) > 0
    }
    def downgradeVersions = latestVersions.findAll { label, version ->
      comparator.compare(version, currentVersions[label]) < 0
    }
    [currentVersions, latestVersions, sameVersions, downgradeVersions, upgradeVersions]
  }

  /** Returns the dependency's group and name. */
  def String label(dependency) { dependency.group + ':' + dependency.name }

  /** Returns the resolution revision level. */
  def revisionLevel() { System.properties.get('revision', revision) }

  /* ---------------- Display Report -------------- */

  /** Prints the report to the console. */
  def displayReport(currentVersions, latestVersions, sameVersions,
      downgradeVersions, upgradeVersions, unresolved) {
    displayHeader()
    displayUpToDate(sameVersions)
    displayExceedLatestFound(currentVersions, downgradeVersions)
    displayUpgrades(currentVersions, upgradeVersions)
    displayUnresolved(unresolved)
  }

  def displayHeader() {
    println """
      |------------------------------------------------------------
      |${project.path} Project Dependency Updates
      |------------------------------------------------------------""".stripMargin()
  }

  def displayUpToDate(sameVersions) {
    if (sameVersions.isEmpty()) {
      println "\nAll dependencies have newer versions."
    } else {
      println "\nThe following dependencies are using the newest ${revisionLevel()} version:"
      sameVersions.sort().each { println " - ${it.key}:${it.value}" }
    }
  }

  def displayExceedLatestFound(currentVersions, downgradeVersions) {
    if (!downgradeVersions.isEmpty()) {
      println("\nThe following dependencies exceed the version found at the "
        + revisionLevel() + " revision level:")
      downgradeVersions.sort().each { label, version ->
        def currentVersion = currentVersions[label]
        println " - ${label} [${currentVersion} <- ${version}]"
      }
    }
  }

  def displayUpgrades(currentVersions, upgradeVersions) {
    if (upgradeVersions.isEmpty()) {
      println "\nAll dependencies are using the latest ${revisionLevel()} versions."
    } else {
      println("\nThe following dependencies have newer ${revisionLevel()} versions:")
      upgradeVersions.sort().each { label, version ->
        def currentVersion = currentVersions[label]
        println " - ${label} [${currentVersion} -> ${version}]"
      }
    }
  }

  def displayUnresolved(unresolved) {
    if (!unresolved.isEmpty()) {
      println("\nFailed to determine the latest version for the following dependencies:")
      unresolved.sort { a, b -> label(a.selector) <=> label(b.selector) }.each {
        println " - " + label(it.selector)
        logger.info "The exception that is the cause of unresolved state:", it.problem
      }
    }
  }
}
