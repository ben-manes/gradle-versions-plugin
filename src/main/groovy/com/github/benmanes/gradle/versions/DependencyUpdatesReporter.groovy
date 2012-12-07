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

import static com.github.benmanes.gradle.versions.DependencyUpdates.keyOf

/**
 * A reporter for the results determining dependency updates.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
class DependencyUpdatesReporter {
  def downgradeVersions
  def upgradeVersions
  def currentVersions
  def latestVersions
  def sameVersions
  def unresolved

  def revisionLevel
  def project

  def DependencyUpdatesReporter(project, revisionLevel, currentVersions, latestVersions,
      sameVersions, downgradeVersions, upgradeVersions, unresolved) {
    this.downgradeVersions = downgradeVersions
    this.upgradeVersions = upgradeVersions
    this.currentVersions = currentVersions
    this.latestVersions = latestVersions
    this.revisionLevel = revisionLevel
    this.sameVersions = sameVersions
    this.unresolved = unresolved
    this.project = project
  }

  /** Writes the report to the console. */
  def writeToConsole() {
    writeTo(System.out)
  }

  /** Writes the report to the file. */
  def writeToFile(fileName) {
    def printStream = new PrintStream(fileName)
    try {
      writeTo(printStream)
    } finally {
      printStream.close()
    }
  }

  /** Writes the report to the print stream. The stream is not automatically closed. */
  def writeTo(printStream) {
    writeHeader(printStream)
    writeUpToDate(printStream)
    writeExceedLatestFound(printStream)
    writeUpgrades(printStream)
    writeUnresolved(printStream)
  }


  private def writeHeader(printStream) {
    printStream.println """
      |------------------------------------------------------------
      |${project.path} Project Dependency Updates
      |------------------------------------------------------------""".stripMargin()
  }

  private def writeUpToDate(printStream) {
    if (sameVersions.isEmpty()) {
      printStream.println "\nAll dependencies have newer versions."
    } else {
      printStream.println(
        "\nThe following dependencies are using the newest ${revisionLevel} version:")
      sameVersions
        .sort { a, b -> compareKeys(a.key, b.key) }
        .each { printStream.println " - ${label(it.key)}:${it.value}" }
    }
  }

  private def writeExceedLatestFound(printStream) {
    if (!downgradeVersions.isEmpty()) {
      printStream.println("\nThe following dependencies exceed the version found at the "
        + revisionLevel + " revision level:")
      downgradeVersions
        .sort { a, b -> compareKeys(a.key, b.key) }
        .each { key, version ->
          def currentVersion = currentVersions[key]
          printStream.println " - ${label(key)} [${currentVersion} <- ${version}]"
        }
    }
  }

  private def writeUpgrades(printStream) {
    if (upgradeVersions.isEmpty()) {
      printStream.println "\nAll dependencies are using the latest ${revisionLevel} versions."
    } else {
      printStream.println "\nThe following dependencies have newer ${revisionLevel} versions:"
      upgradeVersions
        .sort { a, b -> compareKeys(a.key, b.key) }
        .each { key, version ->
          def currentVersion = currentVersions[key]
          printStream.println " - ${label(key)} [${currentVersion} -> ${version}]"
        }
    }
  }

  private def writeUnresolved(printStream) {
    if (!unresolved.isEmpty()) {
      printStream.println(
        "\nFailed to determine the latest version for the following dependencies "
        + "(use --info for details):")
      unresolved
        .sort { a, b -> compareKeys(keyOf(a.selector), keyOf(b.selector)) }
        .each {
          printStream.println " - " + label(keyOf(it.selector))
          project.logger.info "The exception that is the cause of unresolved state:", it.problem
        }
    }
  }

  /** Compares the dependency keys. */
  private def compareKeys(a, b) {
    (a['group'] == b['group']) ? a['name'] <=> b['name'] : a['group'] <=> b['group']
  }

  /** Returns the dependency key as a stringified label. */
  private def label(key) { key.group + ':' + key.name }
}
