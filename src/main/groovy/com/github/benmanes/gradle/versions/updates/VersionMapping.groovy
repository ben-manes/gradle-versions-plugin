/*
 * Copyright 2012-2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.gradle.versions.updates

import groovy.transform.CompileStatic
import org.gradle.api.Project
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.Version
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser

/**
 * A mapping of which versions are out of date, up to date, undeclared, or exceed the latest found.
 */
@CompileStatic
class VersionMapping {
  final SortedSet<Coordinate> downgrade = new TreeSet<>()
  final SortedSet<Coordinate> upToDate = new TreeSet<>()
  final SortedSet<Coordinate> upgrade = new TreeSet<>()
  final SortedSet<Coordinate> undeclared = new TreeSet<>()

  final SortedSet<Coordinate> unresolved = new TreeSet<>()
  final SortedSet<Coordinate> current = new TreeSet<>()
  final SortedSet<Coordinate> latest = new TreeSet<>()

  final Comparator<String> comparator
  final Project project

  VersionMapping(Project project, Set<DependencyStatus> statuses) {
    this.project = project
    this.comparator = makeVersionComparator()
    for (status in statuses) {
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
      project.logger.info("Comparing dependency (current: {}, latest: {})",
        coordinate, latestCoordinate?.version ?: "unresolved")

      if (unresolved.contains(coordinate)) {
        continue
      } else if (coordinate.version == "none") {
        undeclared.add(coordinate)
        continue
      }
      int result = comparator.compare(coordinate.version, latestCoordinate.version)
      if (result <= -1) {
        upgrade.add(coordinate)
      } else if (result == 0) {
        upToDate.add(coordinate)
      } else {
        downgrade.add(coordinate)
      }
    }
  }

  private static Comparator<String> makeVersionComparator() {
    Comparator<Version> baseComparator = new DefaultVersionComparator().asVersionComparator()
    VersionParser versionParser = new VersionParser()
    return new Comparator<String>() {
      int compare(String string1, String string2) {
        return baseComparator.compare(
          versionParser.transform(string1),
          versionParser.transform(string2))
      }
    }
  }
}
