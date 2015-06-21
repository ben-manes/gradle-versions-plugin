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

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.transform.TypeChecked
import org.gradle.api.artifacts.UnresolvedDependency

/**
 * The version status of a dependency.
 * <p>
 * The <tt>latestVersion</tt> is set if the dependency was successfully resolved, otherwise the
 * <tt>unresolved</tt> contains the exception that caused the resolution to fail.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
@ToString
@TypeChecked
@EqualsAndHashCode
class DependencyStatus {
  final UnresolvedDependency unresolved
  final Coordinate coordinate
  final String latestVersion

  DependencyStatus(Coordinate coordinate, String latestVersion) {
    this.latestVersion = latestVersion
    this.coordinate = coordinate
  }

  DependencyStatus(Coordinate coordinate, UnresolvedDependency unresolved) {
    this.coordinate = coordinate
    this.unresolved = unresolved
    this.latestVersion = 'none'
  }

  Coordinate getLatestCoordinate() {
    return new Coordinate(coordinate?.groupId, coordinate?.artifactId, latestVersion)
  }
}
