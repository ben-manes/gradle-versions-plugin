/*
 * Copyright 2012-2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.reporter.Reporter
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.DependencyUpdates
import org.gradle.api.artifacts.Dependency
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

/**
 * A specification for the dependency updates task.
 *
 * @author Ben Manes (ben@addepar.com)
 * @author Zenedith (zenedith@wp.pl)
 */
class DependencyUpdatesSpec extends Specification {

  def 'Single project with no dependencies for many formatters'() {
    given:
      def project = singleProject()
    when:
      def reporter = evaluate(project, 'milestone', 'json,xml')
      reporter.write()
    then:
      with(reporter) {
        unresolved.isEmpty()
        upgradeVersions.isEmpty()
        upToDateVersions.isEmpty()
        downgradeVersions.isEmpty()
      }
  }

  @Unroll('Single project with no dependencies (#outputFormatter)')
  def 'Single project with no dependencies'() {
    given:
      def project = singleProject()
    when:
      def reporter = evaluate(project, 'milestone', outputFormatter)
      reporter.write()
    then:
      with(reporter) {
        unresolved.isEmpty()
        upgradeVersions.isEmpty()
        upToDateVersions.isEmpty()
        downgradeVersions.isEmpty()
      }
    where:
      outputFormatter << ['plain', 'json', 'xml']
  }

  def 'Single project with no dependencies in invalid dir name'() {
    given:
      def project = singleProject()
    when:
      def reporter = evaluate(project, 'milestone', 'json', 'build/invalid dir')
      reporter.write()
    then:
      with(reporter) {
        unresolved.isEmpty()
        upgradeVersions.isEmpty()
        upToDateVersions.isEmpty()
        downgradeVersions.isEmpty()
      }
  }

  @Unroll('Single project with no repositories (#outputFormatter)')
  def 'Single project with no repositories'() {
    given:
      def project = singleProject()
      addDependenciesTo(project)
    when:
      def reporter = evaluate(project, 'milestone', outputFormatter)
      reporter.write()
    then:
      with(reporter) {
        unresolved.size() == 8
        upgradeVersions.isEmpty()
        upToDateVersions.isEmpty()
        downgradeVersions.isEmpty()
      }
    where:
      outputFormatter << ['plain', 'json', 'xml']
  }

  def 'Single project with a good and bad repository'() {
    given:
      def project = singleProject()
      addRepositoryTo(project)
      addBadRepositoryTo(project)
      addDependenciesTo(project)
    when:
      def reporter = evaluate(project)
      reporter.write()
    then:
      with(reporter) {
        unresolved.size() == 2
        upgradeVersions.size() == 2
        upToDateVersions.size() == 2
        downgradeVersions.size() == 2
      }
  }

  @Unroll('Single project (#revision, #outputFormatter)')
  def 'Single project'() {
    given:
      def project = singleProject()
      addRepositoryTo(project)
      addDependenciesTo(project)
    when:
      def reporter = evaluate(project, revision, outputFormatter)
      reporter.write()
    then:
      with(reporter) {
        unresolved.size() == 2
        upgradeVersions.size() == 2
        upToDateVersions.size() == 2
        downgradeVersions.size() == 2
      }
    where:
      revision << ['release', 'milestone', 'integration']
      outputFormatter << ['plain', 'json', 'xml']
  }

  @Unroll('Single project with Beta Updates (#revision, #outputFormatter)')
  def 'Single project with Beta Updates'() {
    given:
      def project = singleProject()
      project.versionsConfig.lookupRevisionMapper = { Dependency dep -> '(,16[' }
      addRepositoryTo(project)
      addDependenciesWithBetaTo(project)
    when:
      def reporter = evaluate(project, revision, outputFormatter)
      reporter.write()
    then:
      with(reporter) {
          upToDateVersions.size() == 2
      }
    where:
      revision << ['release', 'milestone', 'integration']
      outputFormatter << ['plain', 'json', 'xml']
  }

  @Unroll('Multi-project with repository on parent (#revision, #outputFormatter)')
  def 'Multi-project with repository on parent'() {
    given:
      def (rootProject, childProject) = multiProject()
      addRepositoryTo(rootProject)
      addDependenciesTo(childProject)
    when:
      def reporter = evaluate(rootProject, revision, outputFormatter)
      reporter.write()
    then:
      with(reporter) {
        unresolved.size() == 2
        upgradeVersions.size() == 2
        upToDateVersions.size() == 2
        downgradeVersions.size() == 2
      }
    where:
      revision << ['release', 'milestone', 'integration']
      outputFormatter << ['plain', 'json', 'xml']
  }

  @Unroll('Multi-project with repository on child (#revision, #outputFormatter)')
  def 'Multi-project with repository on child'() {
    given:
      def (rootProject, childProject) = multiProject()
      addRepositoryTo(childProject)
      addDependenciesTo(rootProject)
    when:
      def reporter = evaluate(rootProject, revision, outputFormatter)
      reporter.write()
    then:
      with(reporter) {
        unresolved.size() == 2
        upgradeVersions.size() == 2
        upToDateVersions.size() == 2
        downgradeVersions.size() == 2
      }
    where:
      revision << ['release', 'milestone', 'integration']
      outputFormatter << ['plain', 'json', 'xml']
  }

  def 'Version ranges are correctly evaluated'() {
    given:
    def project = singleProject()
    addRepositoryTo(project)
    project.configurations {
      upToDate
    }
    project.dependencies.upToDate 'backport-util-concurrent:backport-util-concurrent:3.+'
  when:
    def reporter = evaluate(project)
    reporter.write()
  then:
    with(reporter) {
      unresolved.isEmpty()
      upgradeVersions.isEmpty()
      upToDateVersions.size() == 1
      downgradeVersions.isEmpty()
    }

  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/26')
  def 'Dependencies without versions do not cause a NPE'() {
    given:
    def project = singleProject()
    addRepositoryTo(project)
    project.configurations {
      upgradesFound
    }
    project.dependencies.upgradesFound 'backport-util-concurrent:backport-util-concurrent'
  when:
    def reporter = evaluate(project)
    reporter.write()
  then:
    with(reporter) {
      unresolved.size() == 1
      upgradeVersions.isEmpty()
      upToDateVersions.isEmpty()
      downgradeVersions.isEmpty()
    }

  }

  def 'Single project with a custom Reporter'() {
	given:
	  def project = singleProject()
	  addRepositoryTo(project)
	  addDependenciesTo(project)
	  Reporter customReporter = Mock()
	when:
	  def reporter = evaluate(project, 'release', customReporter)
	  reporter.write()
	then:
	  1 * customReporter.write(_) { Result result ->
		  result.current.count == 2
		  result.outdated.count == 2
		  result.exceeded.count == 2
		  result.unresolved.count == 2
	  }
  }

  def 'Single project with a Closure as Reporter'() {
	  given:
		def project = singleProject()
		addRepositoryTo(project)
		addDependenciesTo(project)
		int current = -1
		int outdated = -1
		int exceeded = -1
		int unresolved = -1

		def customReporter = { result ->
			current = result.current.count
			outdated = result.outdated.count
			exceeded = result.exceeded.count
			unresolved = result.unresolved.count

		}
	  when:
		def reporter = evaluate(project, 'release', customReporter)
		reporter.write()
	  then:
	    current == 2
	    outdated == 2
	    exceeded == 2
	    unresolved == 2
	}

  def singleProject() {
    def project = new ProjectBuilder().withName('single').build()
    new VersionsPlugin().apply(project)
    project
  }

  def multiProject() {
    def rootProject = new ProjectBuilder().withName('root').build()
    def childProject = new ProjectBuilder().withName('child').withParent(rootProject).build()
    def leafProject = new ProjectBuilder().withName('leaf').withParent(childProject).build()
    new VersionsPlugin().apply(rootProject)
    [rootProject, childProject, leafProject]
  }

  def evaluate(project, revision = 'milestone', outputFormatter = 'plain', outputDir = 'build') {
    new DependencyUpdates(project, revision, outputFormatter, outputDir).run()
  }

  def addRepositoryTo(project) {
    def localMavenRepo = getClass().getResource('/maven/')
    project.repositories {
      maven {
        url localMavenRepo.toURI()
      }
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

  def addDependenciesWithBetaTo(project) {
    project.configurations {
      betaUpgradesFound
    }
    project.dependencies {
      betaUpgradesFound 'com.google.guava:guava:15.0',
        'com.google.guava:guava-tests:15.0'
      }
  }
}
