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
package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.*
import groovy.transform.TupleConstructor
import groovy.transform.TypeChecked

import static groovy.transform.TypeCheckingMode.SKIP

/**
 * A plain text reporter for the dependency updates results.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
@TypeChecked
@TupleConstructor(callSuper = true, includeSuperProperties = true, includeSuperFields = true)
class PlainTextReporter extends AbstractReporter {

  /** Writes the report to the print stream. The stream is not automatically closed. */
  def write(printStream, Result result) {
    writeHeader(printStream)

    if (result.count == 0) {
      printStream.println '\nNo dependencies found.'
    } else {
      writeUpToDate(printStream, result)
      writeExceedLatestFound(printStream, result)
      writeUpgrades(printStream, result)
      writeUnresolved(printStream, result)
    }
  }

  @Override
  def getFileName() {
    return 'report.txt'
  }

  private def writeHeader(printStream) {
    printStream.println """
      |------------------------------------------------------------
      |${project.path} Project Dependency Updates (report to plain text file)
      |------------------------------------------------------------""".stripMargin()
  }

  private def writeUpToDate(printStream, Result result) {
    SortedSet<Dependency> upToDateVersions = result.current.dependencies
    if (!upToDateVersions.isEmpty()) {
      printStream.println(
          "\nThe following dependencies are using the latest ${revision} version:")
      upToDateVersions.each { printStream.println " - ${label(it)}:${it.version}" }
    }
  }

  private def writeExceedLatestFound(printStream, Result result) {
    def downgradeVersions = result.exceeded.dependencies
    if (!downgradeVersions.isEmpty()) {
      printStream.println('\nThe following dependencies exceed the version found at the '
          + revision + ' revision level:')
      downgradeVersions.each { DependencyLatest dep ->
        def currentVersion = dep.version
        printStream.println " - ${label(dep)} [${currentVersion} <- ${dep.latest}]"
      }
    }
  }

  @TypeChecked(SKIP)
  private def writeUpgrades(printStream, Result result) {
    def upgradeVersions = result.outdated.dependencies
    if (!upgradeVersions.isEmpty()) {
      printStream.println "\nThe following dependencies have later ${revision} versions:"
      upgradeVersions.each { DependencyOutdated dep ->
        def currentVersion = dep.version
        printStream.println " - ${label(dep)} [${currentVersion} -> ${dep.available[revision]}]"
      }
    }
  }

  private def writeUnresolved(printStream, Result result) {
    def unresolved = result.unresolved.dependencies
    if (!unresolved.isEmpty()) {
      printStream.println(
          '\nFailed to determine the latest version for the following dependencies '
          + '(use --info for details):')
      unresolved.each { DependencyUnresolved dep ->
        printStream.println ' - ' + label(dep)
        project.logger.info 'The exception that is the cause of unresolved state: {}', dep.reason
      }
    }
  }

  /** Returns the dependency key as a stringified label. */
  private def label(Dependency dependency) {
    dependency.group + ':' + dependency.name
  }
}
