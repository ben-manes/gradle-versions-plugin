/*
 * Copyright 2013 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.gradle.versions.analyze

import groovy.transform.TupleConstructor
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalysis

import static com.github.benmanes.gradle.versions.updates.DependencyUpdates.keyOf


/**
 * A reporter for the dependency analysis results.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
@TupleConstructor
class DependencyAnalyzeReporter {
  /** The project evaluated against. */
  def project
  /** The dependency analysis results. */
  def ProjectDependencyAnalysis analysis

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
    writeUsedDeclaredArtifacts(printStream)
    writeUsedUndeclaredArtifacts(printStream)
    writeUnusedDeclaredArtifacts(printStream)
  }

  private def writeHeader(printStream) {
    printStream.println """
      |------------------------------------------------------------
      |${project.path} Project Dependency Analysis
      |------------------------------------------------------------""".stripMargin()
  }

  private def writeUsedDeclaredArtifacts(printStream) {
    printStream.println "Used declared artifacts:"
    analysis.usedDeclaredArtifacts.each {
      printStream.println "\t$it"
    }
  }

  private def writeUsedUndeclaredArtifacts(printStream) {
    printStream.println "Used undeclared artifacts:"
    analysis.usedUndeclaredArtifacts.each {
      printStream.println "\t$it"
    }
  }

  private def writeUnusedDeclaredArtifacts(printStream) {
    printStream.println "Unused declared artifacts:"
    analysis.unusedDeclaredArtifacts.each {
      printStream.println "\t$it"
    }
  }
}
