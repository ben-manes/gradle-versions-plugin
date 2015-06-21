/*
 * Copyright 2012-2015 Ben Manes. All Rights Reserved.
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

import groovy.transform.TypeChecked
import org.gradle.api.Project
import org.gradle.api.artifacts.UnresolvedDependency

/**
 * A mapping of which versions are out of date, up to date, or exceed the latest found.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
@TypeChecked
class VersionMapping {
  final Set<Coordinate> downgrade = []
  final Set<Coordinate> upToDate = []
  final Set<Coordinate> upgrade = []

  final Set<Coordinate> unresolved = []
  final Set<Coordinate> current = []
  final Set<Coordinate> latest = []

  final VersionComparator comparator;
  final Project project

  VersionMapping(Project project, Set<DependencyStatus> statuses) {
    this.project = project
    this.comparator = new VersionComparator(project)
    statuses.each { status ->
      current.add(status.coordinate)
      if (status.unresolved == null) {
        latest.add(status.latestCoordinate)
      } else {
        unresolved.add(status.coordinate)
      }
    }
    organize()
  }

  /** Groups the dependencies into up-to-date, upgrades available, or downgrade buckets. */
  private void organize() {
    Map<Coordinate.Key, Coordinate> latestByKey = latest.collectEntries { [it.key, it] }

    for (Coordinate coordinate : current) {
      Coordinate latestCoordinate = latestByKey[coordinate.key]
      project.logger.info('Comparing dependency (current: {}, latest: {})',
        coordinate, latestCoordinate?.version ?: 'unresolved')

      if (unresolved.contains(coordinate)) {
        continue
      } else if (coordinate.version == 'none') {
        upToDate.add(coordinate)
        continue
      }
      int result = comparator.compare(coordinate.version, latestCoordinate.version)
      if (result <= -1) {
        upgrade.add(latestCoordinate)
      } else if (result == 0) {
        upToDate.add(latestCoordinate)
      } else {
        downgrade.add(latestCoordinate)
      }
    }
  }
}
