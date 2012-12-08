/*
 * Copyright 2012 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.gradle.versions;

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

/**
 * A specification for the dependency updates task.
 *
 * @author Ben Manes (ben@addepar.com)
 */
class DependencyUpdatesSpec extends Specification {

  def 'Single project with no dependencies'() {
    given:
      def project = singleProject()
    when:
      def reporter = evaluate(project)
      reporter.writeToConsole()
    then:
      with(reporter) {
        unresolved.isEmpty()
        upgradeVersions.isEmpty()
        upToDateVersions.isEmpty()
        downgradeVersions.isEmpty()
      }
  }

  def 'Single project with no repositories'() {
    given:
      def project = singleProject()
      addDependenciesTo(project)
    when:
      def reporter = evaluate(project)
      reporter.writeToConsole()
    then:
      with(reporter) {
        unresolved.size() == 8
        upgradeVersions.isEmpty()
        upToDateVersions.isEmpty()
        downgradeVersions.isEmpty()
      }
  }

  def 'Single project with a good and bad repository'() {
    given:
      def project = singleProject()
      addRepositoryTo(project)
      addBadRepositoryTo(project)
      addDependenciesTo(project)
    when:
      def reporter = evaluate(project)
      reporter.writeToConsole()
    then:
      with(reporter) {
        unresolved.size() == 2
        upgradeVersions.size() == 2
        upToDateVersions.size() == 2
        downgradeVersions.size() == 2
      }
  }

  def 'Single project'() {
    given:
      def project = singleProject()
      addRepositoryTo(project)
      addDependenciesTo(project)
    when:
      def reporter = evaluate(project, revision)
      reporter.writeToConsole()
    then:
      with(reporter) {
        unresolved.size() == 2
        upgradeVersions.size() == 2
        upToDateVersions.size() == 2
        downgradeVersions.size() == 2
      }
    where:
      revision = ['release', 'milestone', 'integration']
  }

  def 'Multi-project with repository on parent'() {
    given:
      def (rootProject, childProject) = multiProject()
      addRepositoryTo(rootProject)
      addDependenciesTo(childProject)
    when:
      def reporter = evaluate(rootProject, revision)
      reporter.writeToConsole()
    then:
      with(reporter) {
        unresolved.size() == 2
        upgradeVersions.size() == 2
        upToDateVersions.size() == 2
        downgradeVersions.size() == 2
      }
    where:
      revision = ['release', 'milestone', 'integration']
  }

  def 'Multi-project with repository on child'() {
    given:
      def (rootProject, childProject, leafProject) = multiProject()
      addRepositoryTo(childProject)
      addDependenciesTo(leafProject)
    when:
      def reporter = evaluate(rootProject, revision)
      reporter.writeToConsole()
    then:
      with(reporter) {
        unresolved.size() == 2
        upgradeVersions.size() == 2
        upToDateVersions.size() == 2
        downgradeVersions.size() == 2
      }
    where:
      revision = ['release', 'milestone', 'integration']
  }

  def singleProject() {
    new ProjectBuilder().withName('single').build()
  }

  def multiProject() {
    def rootProject = new ProjectBuilder().withName('root').build()
    def childProject = new ProjectBuilder().withName('child').withParent(rootProject).build()
    def leafProject = new ProjectBuilder().withName('leaf').withParent(childProject).build()
    [rootProject, childProject, leafProject]
  }

  def evaluate(project, revision = 'milestone') {
    new DependencyUpdates(project, revision).run()
  }

  def addRepositoryTo(project) {
    project.repositories {
      mavenCentral()
    }
  }

  def addBadRepositoryTo(project) {
    project.repositories {
      maven { url = 'http://www.example.com' }
    }
  }

  def addDependenciesTo(project) {
    project.configurations {
      upToDate
      exceedLatest
      upgradesFound
      unresolvable
    }
    project.dependencies {
      upToDate 'backport-util-concurrent:backport-util-concurrent:3.1',
               'backport-util-concurrent:backport-util-concurrent-java12:3.1'
      exceedLatest 'com.google.guava:guava:99.0-SNAPSHOT',
                   'com.google.guava:guava-tests:99.0-SNAPSHOT'
      upgradesFound 'com.google.inject:guice:2.0',
                    'com.google.inject.extensions:guice-multibindings:2.0'
      unresolvable 'com.github.ben-manes:unresolvable:1.0',
                   'com.github.ben-manes:unresolvable2:1.0'
    }
  }
}
