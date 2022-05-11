package com.github.benmanes.gradle.versions.updates

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

open class BaseDependencyUpdates {

  fun resolve(
    resolver: BaseResolver,
    project: Project,
    config: Configuration,
    revision: String
  ): Set<DependencyStatus> {
    return try {
      resolver.resolve(config, revision)
    } catch (e: Exception) {
      project.logger.info("Skipping configuration ${project.path}:${config.name}", e)
      emptySet()
    }
  }

  companion object {
    /**
     * A new status will be added if either,
     * <ol>
     *   <li>[Coordinate.Key] of new status is not yet present in status collection
     *   <li>new status has concrete version (not `none`); the old status will then be removed
     *       if its coordinate is `none` versioned</li>
     * </ol>
     */
    @JvmStatic
    fun addValidatedDependencyStatus(
      statusCollection: HashSet<DependencyStatus>,
      status: DependencyStatus
    ) {
      val statusWithSameCoordinateKey = statusCollection.find {
        it.coordinate.key == status.coordinate.key
      }
      if (statusWithSameCoordinateKey == null) {
        statusCollection.add(status)
      } else if (status.coordinate.version != "none") {
        statusCollection.add(status)
        if (statusWithSameCoordinateKey.coordinate.version == "none") {
          statusCollection.remove(statusWithSameCoordinateKey)
        }
      }
    }

    @JvmStatic
    fun toMap(coordinates: Set<Coordinate>): Map<Map<String, String>, Coordinate> {
      val map = HashMap<Map<String, String>, Coordinate>()
      for (coordinate in coordinates) {
        var i = 0
        while (true) {
          val artifactId = coordinate.artifactId + if (i == 0) "" else "[${i + 1}]"
          val keyMap = linkedMapOf<String, String>().apply {
            put("group", coordinate.groupId)
            put("name", artifactId)
          }
          if (!map.containsKey(keyMap)) {
            map[keyMap] = coordinate
            break
          }

          ++i
        }
      }
      return map
    }
  }
}
