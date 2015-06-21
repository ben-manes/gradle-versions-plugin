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
import org.gradle.api.GradleException
import org.gradle.api.Project

import static groovy.transform.TypeCheckingMode.SKIP

/**
 * A comparator of the dependency's version to determine which is later.
 *
 * @author Jochen Berger
 * @author Ben Manes (ben.manes@gmail.com)
 */
@TypeChecked
class VersionComparator implements Comparator<String> {
  static final String GRADLE_24 =
    'org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator'
  static final String GRADLE_23 =
    'org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.StaticVersionComparator'
  static final String GRADLE_18 =
    'org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.ResolverStrategy'
  static final String GRADLE_10 =
    'org.gradle.api.internal.artifacts.version.LatestVersionSemanticComparator'

  final Comparator<String> delegate;

  VersionComparator(Project project) {
    delegate = getGradleVersionComparator(project)
  }

  @Override
  public int compare(String first, String second) {
    return delegate.compare(first, second)
  }

  /** Returns the internal version comparator compatible with the Gradle version. */
  private Comparator<String> getGradleVersionComparator(Project project) {
    for (Closure<Comparator<String>> factory : candidates()) {
      try {
        return factory.call()
      } catch (Exception ignored) {}
    }
    String gradleVersion = project.gradle.gradleVersion
    throw new GradleException("Could not create a version comparator for Gradle ${gradleVersion}")
  }

  /** Returns a list of factories for creating Gradle version-specific comparators. */
  @TypeChecked(SKIP)
  private List<Closure<Comparator<String>>> candidates() {
    return [
      { createInstance(GRADLE_24).asStringComparator() },
      { createInstance(GRADLE_23) },
      { createInstance(GRADLE_18).getVersionMatcher() },
      { createInstance(GRADLE_10) }
    ]
  }

  /** Creates a new instance of the given class. */
  Closure<Object> createInstance = { String className ->
    def classLoader = Thread.currentThread().getContextClassLoader()
    return classLoader.loadClass(className).newInstance()
  }
}
