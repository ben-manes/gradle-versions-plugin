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

import groovy.transform.TupleConstructor

import com.github.benmanes.gradle.versions.reporter.JsonReporter
import com.github.benmanes.gradle.versions.reporter.PlainTextReporter
import com.github.benmanes.gradle.versions.reporter.Reporter
import com.github.benmanes.gradle.versions.reporter.XmlReporter
import com.github.benmanes.gradle.versions.reporter.result.DependenciesGroup
import com.github.benmanes.gradle.versions.reporter.result.Dependency
import com.github.benmanes.gradle.versions.reporter.result.DependencyLatest
import com.github.benmanes.gradle.versions.reporter.result.DependencyOutdated
import com.github.benmanes.gradle.versions.reporter.result.DependencyUnresolved
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.reporter.result.VersionAvailable

/**
 * A reporter for the dependency updates results.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 * @author Zenedith (zenedith@wp.pl)
 */
@TupleConstructor
class DependencyUpdatesReporter {
  /** The project evaluated against. */
  def project
  /** The revision strategy evaluated with. */
  def revision
  /** The output formatter strategy evaluated with. */
  def outputFormatter
  /** The outputDir for report. */
  def outputDir

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

  private static final Object MUTEX = new Object()

  def write() {
    synchronized (MUTEX) {
      def plainTextReporter = new PlainTextReporter(project, revision)

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
    def filename = outputDir + '/' + reporter.getFileName()
    def reporterFileStream

    try {
      new File(outputDir).mkdirs()
      reporterFileStream = new PrintStream(filename)
	  def result = buildBaseObject()
      reporter.write(reporterFileStream, result)
      project.logger.lifecycle '\nGenerated report file ' + filename
    }
    catch (FileNotFoundException e) {
      project.logger.error 'Invalid outputDir path ' + filename
    }
    finally {
      if (reporterFileStream != null) {
        reporterFileStream.close()
      }
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

   def buildBaseObject() {
    def current = buildCurrentGroup()
    def outdated = buildOutdatedGroup()
    def exceeded = buildExceededGroup()
    def unresolved = buildUnresolvedGroup()

    def count = current.size() + outdated.size() + exceeded.size() + unresolved.size()

    buildObject(
        count,
        buildDependenciesGroup(current),
        buildDependenciesGroup(outdated),
        buildDependenciesGroup(exceeded),
        buildDependenciesGroup(unresolved)
    )
  }

  protected def buildCurrentGroup() {
	sortByGroupAndName(upToDateVersions).collect { dep ->
		buildDependency(dep.key['name'], dep.key['group'], dep.value)
	}
  }

  protected def buildOutdatedGroup() {
	sortByGroupAndName(upgradeVersions).collect { dep ->
		buildOutdatedDependency(dep.key['name'], dep.key['group'], currentVersions[dep.key], dep.value)
	}
  }

  protected def buildExceededGroup() {
	sortByGroupAndName(downgradeVersions).collect { dep ->
		buildExceededDependency(dep.key['name'], dep.key['group'], currentVersions[dep.key], dep.value)
	}
  }

  protected def buildUnresolvedGroup() {
	unresolved.sort { a, b ->
		compareKeys(keyOf(a.selector), keyOf(b.selector))
	}.collect { dep ->
		def message = dep.problem.getMessage()
		def split = message.split('Required by')

		if (split.length > 0) {
			message = split[0].trim()
		}
		buildUnresolvedDependency(dep.selector['name'], dep.selector['group'], currentVersions[keyOf(dep.selector)], message)
    }
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
        break
      case 'integration':
        available = new VersionAvailable(null, null, laterVersion)
        break
      default:
        available = new VersionAvailable(laterVersion)
    }

    new DependencyOutdated(name, group, version, available)
  }

  def sortByGroupAndName(dependencies) {
    dependencies.sort { a, b -> compareKeys(a.key, b.key) }
  }

/** Compares the dependency keys. */
  protected def compareKeys(a, b) {
    (a['group'] == b['group']) ? a['name'] <=> b['name'] : a['group'] <=> b['group']
  }

  def static keyOf(dependency) { [group: dependency.group, name: dependency.name] }

}
