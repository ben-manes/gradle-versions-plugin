package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.Dependency
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.CURRENT
import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.NIGHTLY
import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE
import org.gradle.api.Project
import java.io.OutputStream

/**
 * A plain text reporter for the dependency updates results.
 */
class PlainTextReporter(
  override val project: Project,
  override val revision: String,
  override val gradleReleaseChannel: String,
) : AbstractReporter(project, revision, gradleReleaseChannel) {
  /** Writes the report to the print stream. The stream is not automatically closed. */
  override fun write(printStream: OutputStream, result: Result) {
    writeHeader(printStream)

    if (result.count == 0) {
      printStream.println()
      printStream.println("No dependencies found.")
    } else {
      writeUpToDate(printStream, result)
      writeExceedLatestFound(printStream, result)
      writeUpgrades(printStream, result)
      writeUndeclared(printStream, result)
      writeUnresolved(printStream, result)
    }

    writeGradleUpdates(printStream, result)
  }

  override fun getFileExtension(): String {
    return "txt"
  }

  private fun writeHeader(printStream: OutputStream) {
    printStream.println()
    printStream.println("------------------------------------------------------------")
    printStream.println("${project.path} Project Dependency Updates (report to plain text file)")
    printStream.println("------------------------------------------------------------")
  }

  private fun writeUpToDate(printStream: OutputStream, result: Result) {
    val upToDateVersions = result.current.dependencies
    if (upToDateVersions.isNotEmpty()) {
      printStream.println()
      printStream.println("The following dependencies are using the latest $revision version:")
      for (dependency in upToDateVersions) {
        printStream.println(" - ${label(dependency)}:${dependency.version}")
        dependency.userReason?.let {
          printStream.println("     $it")
        }
      }
    }
  }

  private fun writeExceedLatestFound(printStream: OutputStream, result: Result) {
    val downgradeVersions = result.exceeded.dependencies
    if (downgradeVersions.isNotEmpty()) {
      printStream.println()
      printStream.println(
        "The following dependencies exceed the version found at the $revision revision level:"
      )
      for (dependency in downgradeVersions) {
        val currentVersion = dependency.version
        printStream.println(" - ${label(dependency)} [$currentVersion <- ${dependency.latest}]")
        dependency.userReason?.let {
          printStream.println("     $it")
        }
        dependency.projectUrl?.let {
          printStream.println("     $it")
        }
      }
    }
  }

  private fun writeUpgrades(printStream: OutputStream, result: Result) {
    val upgradeVersions = result.outdated.dependencies
    if (upgradeVersions.isNotEmpty()) {
      printStream.println()
      printStream.println("The following dependencies have later $revision versions:")
      for (dependency in upgradeVersions) {
        val currentVersion = dependency.version
        printStream.println(" - ${label(dependency)} [$currentVersion -> ${dependency.available[revision]}]")
        dependency.userReason?.let {
          printStream.println("     $it")
        }
        dependency.projectUrl?.let {
          printStream.println("     $it")
        }
      }
    }
  }

  private fun writeUndeclared(printStream: OutputStream, result: Result) {
    val undeclaredVersions = result.undeclared.dependencies
    if (undeclaredVersions.isNotEmpty()) {
      printStream.println()
      printStream.println(
        "Failed to compare versions for the following dependencies because they were declared without version:"
      )
      for (dependency in undeclaredVersions) {
        printStream.println(" - ${label(dependency)}")
      }
    }
  }

  private fun writeUnresolved(printStream: OutputStream, result: Result) {
    val unresolved = result.unresolved.dependencies
    if (unresolved.isNotEmpty()) {
      printStream.println()
      printStream.println(
        "Failed to determine the latest version for the following dependencies (use --info for details):"
      )
      for (dependency in unresolved) {
        printStream.println(" - " + label(dependency))
        dependency.userReason?.let {
          printStream.println("     $it")
        }
        dependency.projectUrl?.let {
          printStream.println("     $it")
        }
        project.logger.info(
          "The exception that is the cause of unresolved state: {}",
          dependency.reason
        )
      }
    }
  }

  private fun writeGradleUpdates(printStream: OutputStream, result: Result) {
    if (!result.gradle.enabled) {
      return
    }

    printStream.println()
    printStream.println("Gradle $gradleReleaseChannel updates:")
    // Log Gradle update checking failures.
    if (result.gradle.current.isFailure) {
      printStream
        .println("[ERROR] [release channel: ${CURRENT.id}] " + result.gradle.current.reason)
    }
    if ((gradleReleaseChannel == RELEASE_CANDIDATE.id || gradleReleaseChannel == NIGHTLY.id) &&
      result.gradle.releaseCandidate.isFailure
    ) {
      printStream
        .println(
          "[ERROR] [release channel: ${RELEASE_CANDIDATE.id}] " + result
            .gradle.releaseCandidate.reason
        )
    }
    if (gradleReleaseChannel == NIGHTLY.id && result.gradle.nightly.isFailure) {
      printStream
        .println("[ERROR] [release channel: ${NIGHTLY.id}] " + result.gradle.nightly.reason)
    }

    // print Gradle updates in breadcrumb format
    printStream.print(" - Gradle: [" + result.gradle.running.version)
    var updatePrinted = false
    if (result.gradle.current.isUpdateAvailable && result.gradle.current > result.gradle.running) {
      updatePrinted = true
      printStream.print(" -> " + result.gradle.current.version)
    }
    if ((gradleReleaseChannel == RELEASE_CANDIDATE.id || gradleReleaseChannel == NIGHTLY.id) &&
      result.gradle.releaseCandidate.isUpdateAvailable &&
      result.gradle.releaseCandidate >
      result.gradle.current
    ) {
      updatePrinted = true
      printStream.print(" -> " + result.gradle.releaseCandidate.version)
    }
    if (gradleReleaseChannel == NIGHTLY.id &&
      result.gradle.nightly.isUpdateAvailable &&
      result.gradle.nightly >
      result.gradle.current
    ) {
      updatePrinted = true
      printStream.print(" -> " + result.gradle.nightly.version)
    }
    if (!updatePrinted) {
      printStream.print(": UP-TO-DATE")
    }
    printStream.println("]")
  }

  companion object {
    /** Returns the dependency key as a stringified label. */
    private fun label(dependency: Dependency): String {
      return "${dependency.group}:${dependency.name}"
    }
  }
}
