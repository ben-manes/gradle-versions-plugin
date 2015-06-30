/*
 * Copyright 2012-2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.reporter.JsonReporter
import com.github.benmanes.gradle.versions.reporter.PlainTextReporter
import com.github.benmanes.gradle.versions.reporter.Reporter
import com.github.benmanes.gradle.versions.reporter.XmlReporter
import com.github.benmanes.gradle.versions.reporter.result.*
import groovy.transform.TupleConstructor
import groovy.transform.TypeChecked
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleVersionSelector
import org.gradle.api.artifacts.UnresolvedDependency

/**
 * A reporter for the dependency updates results.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 * @author Zenedith (zenedith@wp.pl)
 */
@TypeChecked
@TupleConstructor
class DependencyUpdatesReporter {
  /** The project evaluated against. */
  Project project
  /** The revision strategy evaluated with. */
  String revision
  /** The output formatter strategy evaluated with. */
  Object outputFormatter
  /** The outputDir for report. */
  String outputDir

  /** The current versions of each dependency declared in the project(s). */
  Map<Map<String, String>, String> currentVersions
  /** The latest versions of each dependency (as scoped by the revision level). */
  Map<Map<String, String>, String> latestVersions

  /** The dependencies that are up to date (same as latest found). */
  Map<Map<String, String>, String> upToDateVersions
  /** The dependencies that exceed the latest found (e.g. may not want SNAPSHOTs). */
  Map<Map<String, String>, String> downgradeVersions
  /** The dependencies where upgrades were found (below latest found). */
  Map<Map<String, String>, String> upgradeVersions
  /** The dependencies that could not be resolved. */
  Set<UnresolvedDependency> unresolved

  private static final Object MUTEX = new Object()

  def write() {
    synchronized (MUTEX) {
      PlainTextReporter plainTextReporter = new PlainTextReporter(project, revision)

      plainTextReporter.write(System.out, buildBaseObject())

      if (outputFormatter == null || (outputFormatter instanceof String && outputFormatter.isEmpty())) {
        project.logger.lifecycle('Skip generating report to file (outputFormatter is empty)')
        return
      }
      if (outputFormatter instanceof String) {
	      outputFormatter.split(',').each {
	        generateFileReport(getOutputReporter(it))
	      }
      } else if (outputFormatter instanceof Reporter) {
	  	generateFileReport(outputFormatter)
      } else if (outputFormatter instanceof Closure) {
	    Result result = buildBaseObject()
	  	outputFormatter.call(result)
      } else {
	  	throw new IllegalArgumentException("Cannot handle output formatter $outputFormatter, unsupported type")
      }
    }
  }

  def generateFileReport(Reporter reporter) {
    String filename = outputDir + '/' + reporter.getFileName()
    try {
      project.file(outputDir).mkdirs()
      File outputFile = project.file(filename)
      outputFile.newOutputStream().with { OutputStream os ->
        new PrintStream(new FileOutputStream(outputFile)).with { PrintStream ps ->
          def result = buildBaseObject()
          reporter.write(ps, result)
        }
      }

      project.logger.lifecycle '\nGenerated report file ' + filename
    }
    catch (FileNotFoundException e) {
      project.logger.error 'Invalid outputDir path ' + filename
    }
  }

  def Reporter getOutputReporter(def formatter) {
    def reporter

    switch (formatter) {
      case 'json':
        reporter = new JsonReporter(project, revision)
        break
      case 'xml':
        reporter = new XmlReporter(project, revision)
        break
      default:
        reporter = new PlainTextReporter(project, revision)
    }

    return reporter
  }

   Result buildBaseObject() {
    List current = buildCurrentGroup()
    List outdated = buildOutdatedGroup()
    List exceeded = buildExceededGroup()
    List unresolved = buildUnresolvedGroup()

    def count = current.size() + outdated.size() + exceeded.size() + unresolved.size()

    buildObject(
        count,
        buildDependenciesGroup(current),
        buildDependenciesGroup(outdated),
        buildDependenciesGroup(exceeded),
        buildDependenciesGroup(unresolved)
    )
  }

  protected List buildCurrentGroup() {
	sortByGroupAndName(upToDateVersions).collect { Map.Entry<Map<String, String>, String> dep ->
		buildDependency(dep.key['name'], dep.key['group'], dep.value)
	}
  }

  protected List buildOutdatedGroup() {
	sortByGroupAndName(upgradeVersions).collect { Map.Entry<Map<String, String>, String> dep ->
    int index = dep.key['name'].lastIndexOf('[')
    dep.key['name'] = (index == -1) ? dep.key['name'] : dep.key['name'].substring(0, index)
		buildOutdatedDependency(dep.key['name'], dep.key['group'], dep.value, latestVersions[dep.key])
	}
  }

  protected List buildExceededGroup() {
	sortByGroupAndName(downgradeVersions).collect { Map.Entry<Map<String, String>, String> dep ->
    int index = dep.key['name'].lastIndexOf('[')
    dep.key['name'] = (index == -1) ? dep.key['name'] : dep.key['name'].substring(0, index)
		buildExceededDependency(dep.key['name'], dep.key['group'], dep.value, latestVersions[dep.key])
	}
  }

  protected List<DependencyUnresolved> buildUnresolvedGroup() {
      unresolved.sort { UnresolvedDependency a, UnresolvedDependency b ->
		compareKeys(keyOf(a.selector), keyOf(b.selector))
	}.collect { UnresolvedDependency dep ->
		def message = dep.problem.getMessage()
		def split = message.split('Required by')

		if (split.length > 0) {
			message = split[0].trim()
		}
		buildUnresolvedDependency(dep.selector.name, dep.selector.group, currentVersions[keyOf(dep.selector)], message)
    }
  }

  protected Result buildObject(int count,
                               DependenciesGroup current,
                               DependenciesGroup outdated,
                               DependenciesGroup exceeded,
                               DependenciesGroup unresolved) {
    new Result(count, current, outdated, exceeded, unresolved)
  }

  protected <T extends Dependency> DependenciesGroup<T> buildDependenciesGroup(List<T> dependencies) {
    new DependenciesGroup<T>(dependencies.size(), dependencies)
  }

  protected def buildDependency(String name, String group, String version) {
    new Dependency(name, group, version)
  }

  protected def buildExceededDependency(String name, String group, String version, String latestVersion) {
    new DependencyLatest(name, group, version, latestVersion)
  }

  protected def buildUnresolvedDependency(String name, String group, String version, String reason) {
    new DependencyUnresolved(name, group, version, reason)
  }

  protected def buildOutdatedDependency(String name, String group, String version, String laterVersion) {
    def available

    switch (revision) {
      case 'milestone':
        available = new VersionAvailable(null, laterVersion)
        break
      case 'integration':
        available = new VersionAvailable(null, null, laterVersion)
        break
      default:
        available = new VersionAvailable(laterVersion)
    }

    new DependencyOutdated(name, group, version, available)
  }

  def sortByGroupAndName(Map<Map<String, String>, String> dependencies) {
    dependencies.sort { Map.Entry<Map<String, String>> a,
                        Map.Entry<Map<String, String>> b -> compareKeys(a.key, b.key) }
  }

/** Compares the dependency keys. */
  protected def compareKeys(Map<String, String> a, Map<String, String> b) {
    (a['group'] == b['group']) ? a['name'] <=> b['name'] : a['group'] <=> b['group']
  }

  static Map<String, String> keyOf(ModuleVersionSelector dependency) {
    [group: dependency.group, name: dependency.name]
  }

}
