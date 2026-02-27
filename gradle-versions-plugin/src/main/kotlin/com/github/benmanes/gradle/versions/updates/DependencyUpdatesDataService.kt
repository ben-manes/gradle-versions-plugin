package com.github.benmanes.gradle.versions.updates

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentHashMap

/**
 * A shared build service that holds pre-resolved [DependencyUpdatesTask.ExecutionData] for
 * all [DependencyUpdatesTask] instances in the current build invocation.
 *
 * Using a build service instead of a static companion-object map is the proper Gradle API
 * for build-scoped data: the service is created per build invocation and is not shared
 * across builds in the same daemon, avoiding JVM-level static leaks.
 *
 * Requires Gradle 6.1+ (when [BuildService] was introduced). On older Gradle versions
 * the plugin falls back to the static [DependencyUpdatesTask.executionDataCache].
 */
abstract class DependencyUpdatesDataService : BuildService<BuildServiceParameters.None> {
  internal val executionDataMap = ConcurrentHashMap<String, DependencyUpdatesTask.ExecutionData>()

  internal companion object {
    const val SERVICE_NAME = "dependencyUpdatesData"
  }
}
