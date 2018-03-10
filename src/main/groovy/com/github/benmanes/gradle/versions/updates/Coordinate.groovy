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
import groovy.transform.TypeChecked
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ModuleVersionSelector

/**
 * The dependency's coordinate.
 */
@TypeChecked
@EqualsAndHashCode
class Coordinate implements Comparable<Coordinate> {
  final String groupId
  final String artifactId
  final String version

  public Coordinate(String groupId, String artifactId, String version) {
    this.groupId = groupId ?: 'none'
    this.artifactId = artifactId ?: 'none'
    this.version = version ?: 'none'
  }

  public Key getKey() {
    return new Key(groupId, artifactId)
  }

  @Override
  public String toString() {
    return groupId + ':' + artifactId + ':' + version
  }

  @Override
  public int compareTo(Coordinate coordinate) {
    int result = key.compareTo(coordinate.key)
    return (result == 0) ? version.compareTo(coordinate.version) : result
  }

  static Coordinate from(ModuleVersionSelector selector) {
    return new Coordinate(selector.group, selector.name, selector.version)
  }

  static Coordinate from(Dependency dependency) {
    return new Coordinate(dependency.group, dependency.name, dependency.version)
  }

  static Coordinate from(ModuleVersionIdentifier identifier) {
    return new Coordinate(identifier.group, identifier.name, identifier.version)
  }

  @EqualsAndHashCode
  static class Key implements Comparable<Key> {
    final String groupId
    final String artifactId

    private Key(String groupId, String artifactId) {
      this.groupId = groupId
      this.artifactId = artifactId
    }

    @Override
    public String toString() {
      return groupId + ':' + artifactId
    }

    @Override
    public int compareTo(Key key) {
      int result = groupId.compareTo(key.groupId)
      return (result == 0) ? artifactId.compareTo(key.artifactId) : result
    }
  }
}
