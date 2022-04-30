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
import groovy.transform.EqualsAndHashCode
import javax.annotation.Nullable
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ModuleVersionSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

/**
 * The dependency's coordinate.
 */
@CompileStatic
@EqualsAndHashCode(excludes = "userReason")
class Coordinate implements Comparable<Coordinate> {
  final String groupId
  final String artifactId
  final String version
  @Nullable final String userReason

  Coordinate(String groupId, String artifactId, String version, @Nullable String userReason) {
    this.groupId = groupId ?: "none"
    this.artifactId = artifactId ?: "none"
    this.version = version ?: "none"
    this.userReason = userReason
  }

  Key getKey() {
    return new Key(groupId, artifactId)
  }

  @Override
  String toString() {
    return groupId + ":" + artifactId + ":" + version
  }

  @Override
  int compareTo(Coordinate coordinate) {
    int result = key <=> coordinate.key
    return (result == 0) ? version <=> coordinate.version : result
  }

  static Coordinate from(ExternalModuleDependency dependency) {
    String userReason = null
    if (dependency.metaClass.respondsTo(dependency, "getReason")) {
      userReason = dependency.getReason()
    }
    return new Coordinate(dependency.group, dependency.name, dependency.version, userReason)
  }

  static Coordinate from(ModuleVersionSelector selector) {
    return new Coordinate(selector.group, selector.name, selector.version, null)
  }

  static Coordinate from(ModuleVersionIdentifier identifier) {
    return new Coordinate(identifier.group, identifier.name, identifier.version, null)
  }

  static Coordinate from(Dependency dependency) {
    String userReason = null
    if (dependency.metaClass.respondsTo(dependency, "getReason")) {
      userReason = dependency.getReason()
    }
    return new Coordinate(dependency.group, dependency.name, dependency.version, userReason)
  }

  static Key keyFrom(ModuleVersionSelector selector) {
    return new Key(selector.group, selector.name)
  }

  static Coordinate from(ModuleVersionIdentifier identifier, Map<Key, Coordinate> declared) {
    return new Coordinate(identifier.group, identifier.name, identifier.version,
      declared?.getOrDefault(new Key(identifier.group, identifier.name), null)?.getUserReason())
  }

  static Coordinate from(ModuleComponentIdentifier identifier) {
    return new Coordinate(identifier.group, identifier.module, identifier.version, null)
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
    String toString() {
      return groupId + ":" + artifactId
    }

    @Override
    int compareTo(Key key) {
      int result = groupId <=> key.groupId
      return (result == 0) ? artifactId <=> key.artifactId : result
    }
  }
}
