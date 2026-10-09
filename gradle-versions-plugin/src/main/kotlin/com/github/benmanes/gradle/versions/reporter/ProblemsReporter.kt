package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.ProblemsApi
import com.github.benmanes.gradle.versions.updates.VersionMapping
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.reflect.ObjectInstantiationException
import org.gradle.util.GradleVersion
import javax.inject.Inject

/**
 * Reports the dependency updates results to Gradle's Problems API, which is incubating and available
 * from Gradle 8.13. On an older Gradle nothing is reported.
 *
 * Gradle's services are injected into it, so it is created with
 * `objects.newInstance(ProblemsReporter::class.java)`.
 */
open class ProblemsReporter
  @Inject
  constructor(private val objects: ObjectFactory) {
    /** Reports each outdated dependency of the result as a problem. */
    fun report(result: Result) {
      if (GradleVersion.current().baseVersion < GradleVersion.version("8.13")) {
        logger.info("The problems report was skipped, as it needs Gradle 8.13 or later")
        return
      }
      try {
        ProblemFindings(objects.newInstance(ProblemsApi::class.java)).report(result)
      } catch (e: ObjectInstantiationException) {
        // The API is incubating, so a Gradle release that changes it loses the problems report
        // rather than failing the build. A missing class is wrapped in this exception while the
        // reporter is created, and thrown as is once it has been.
        warnUnsupported(e)
      } catch (e: LinkageError) {
        warnUnsupported(e)
      }
    }

    /** Warns that the problems report was cut short, which may be before or after its first problem. */
    private fun warnUnsupported(e: Throwable) {
      logger.warn("The problems report is incomplete, as this Gradle's Problems API is not supported", e)
    }

    private companion object {
      val logger = Logging.getLogger(ProblemsReporter::class.java)
    }
  }

/**
 * Reports the findings of a result. A class apart from [ProblemsReporter], so that no class compiled
 * against the Problems API is in a signature of the class that is created on every Gradle release.
 */
private class ProblemFindings(private val problems: ProblemsApi) {
  fun report(result: Result) {
    reportOutdated(result)
  }

  private fun reportOutdated(result: Result) {
    val versionComparator = VersionMapping.versionComparator()
    for (dependency in result.outdated.dependencies) {
      val coordinate = "${dependency.group}:${dependency.name}"
      val available = dependency.available
      // Read from whichever revision the result has, rather than from a revision passed in: one
      // captured when the task is configured is not the one a `--revision` option sets later.
      val latest =
        listOfNotNull(available.release, available.milestone, available.integration).maxWithOrNull(versionComparator)
      // A version printed once for several tiers is described by the last of them, as the text
      // report prints it once for each.
      val tiers =
        listOf(
          available.patch to "the latest patch version",
          available.minor to "the latest minor version",
          latest to "the latest version",
          available.preRelease to "the latest pre-release",
        ).filter { it.first != null }.toMap()
      val steps =
        laterSteps(
          listOf(dependency.version, available.patch, available.minor, latest, available.preRelease),
          versionComparator,
        )
      problems.reportOutdated(
        coordinate,
        "$coordinate [${steps.joinToString(" -> ")}]",
        // The lines printed under the row in the text report, in the same order.
        listOfNotNull(
          dependency.userReason,
          dependency.projectUrl,
          sourceLabel(dependency, dependency.declaringProjects ?: dependency.projects),
        ).joinToString("\n").ifEmpty { null },
        steps.mapNotNull { version -> tiers[version]?.let { "Upgrade $coordinate to $version, $it" } },
      )
    }
  }
}
