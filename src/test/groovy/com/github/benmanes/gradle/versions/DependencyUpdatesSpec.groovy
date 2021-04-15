/*
 * Copyright 2012-2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.reporter.Reporter
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.DependencyUpdates
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.ModuleVersionSelector
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.CURRENT
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE

/**
 * A specification for the dependency updates task.
 */
final class DependencyUpdatesSpec extends Specification {
  def 'Single project with no dependencies for many formats'() {
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

  @Unroll
  def 'Single project with no dependencies (#outputFormat)'() {
    given:
    def project = singleProject()

    when:
    def reporter = evaluate(project, 'milestone', outputFormat)
    reporter.write()

    then:
    with(reporter) {
      unresolved.isEmpty()
      upgradeVersions.isEmpty()
      upToDateVersions.isEmpty()
      downgradeVersions.isEmpty()
    }

    where:
    outputFormat << ['plain', 'json', 'xml']
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

  @Unroll
  def 'Single project with no repositories (#outputFormat)'() {
    given:
    def project = singleProject()
    addDependenciesTo(project)

    when:
    def reporter = evaluate(project, 'milestone', outputFormat)
    reporter.write()

    then:
    with(reporter) {
      unresolved.size() == 8
      upgradeVersions.isEmpty()
      upToDateVersions.isEmpty()
      downgradeVersions.isEmpty()
    }

    where:
    outputFormat << ['plain', 'json', 'xml']
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
    checkUnresolvedVersions(reporter)
    checkUpgradeVersions(reporter)
    checkUpToDateVersions(reporter)
    checkDowngradeVersions(reporter)
  }

  @Unroll
  def 'Single project (#revision, #outputFormat)'() {
    given:
    def project = singleProject()
    addRepositoryTo(project)
    addDependenciesTo(project)

    when:
    def reporter = evaluate(project, revision, outputFormat)
    reporter.write()

    then:
    checkUnresolvedVersions(reporter)
    checkUpgradeVersions(reporter)
    checkUpToDateVersions(reporter)
    checkDowngradeVersions(reporter)

    where:
    revision << ['release', 'milestone', 'integration']
    outputFormat << ['plain', 'json', 'xml']
  }

  @Unroll
  def 'Multi-project with dependencies on parent (#revision, #outputFormat)'() {
    given:
    def (rootProject, childProject) = multiProject()
    addRepositoryTo(rootProject)
    addDependenciesTo(rootProject)

    when:
    def reporter = evaluate(rootProject, revision, outputFormat)
    reporter.write()

    then:
    checkUnresolvedVersions(reporter)
    checkUpgradeVersions(reporter)
    checkUpToDateVersions(reporter)
    checkDowngradeVersions(reporter)

    where:
    revision << ['release', 'milestone', 'integration']
    outputFormat << ['plain', 'json', 'xml']
  }

  @Unroll
  def 'Multi-project with dependencies on child (#revision, #outputFormat)'() {
    given:
    def (rootProject, childProject) = multiProject()
    addRepositoryTo(childProject)
    addDependenciesTo(childProject)

    when:
    def reporter = evaluate(rootProject, revision, outputFormat)
    reporter.write()

    then:
    checkUnresolvedVersions(reporter)
    checkUpgradeVersions(reporter)
    checkUpToDateVersions(reporter)
    checkDowngradeVersions(reporter)

    where:
    revision << ['release', 'milestone', 'integration']
    outputFormat << ['plain', 'json', 'xml']
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
      upToDateVersions.get(['group': 'backport-util-concurrent', 'name': 'backport-util-concurrent'])
        .getVersion().equals('3.1')
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
      unresolved.isEmpty()
      upgradeVersions.isEmpty()
      upToDateVersions.get(['group': 'backport-util-concurrent', 'name': 'backport-util-concurrent'])
        .getVersion().equals('none')
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

  def 'Single project with flatDir repository'() {
    given:
    def project = singleProject()
    project.repositories {
      flatDir {
        dirs getClass().getResource('/libs/')
      }
    }
    project.configurations {
      flat
    }
    project.dependencies {
      flat(name: 'guice-4.0', ext: 'jar')
      flat(name: 'guava-18.0', ext: 'jar')
    }

    when:
    def reporter = evaluate(project)
    reporter.write()

    then:
    with(reporter) {
      unresolved.collect { it.selector }.collectEntries { dependency ->
        [['group': dependency.group, 'name': dependency.name]: dependency.version]
      } == [['group': 'null', 'name': 'guava-18.0']: 'none']
      upgradeVersions.isEmpty()
      upToDateVersions.get(['group': 'null', 'name': 'guice-4.0']).getVersion().equals('none')
      downgradeVersions.isEmpty()
    }
  }

  def 'Single project with configurations scoped dependency versions'() {
    given:
    def project = singleProject()
    addRepositoryTo(project)
    project.configurations {
      upToDate
      exceedLatest
    }
    project.dependencies {
      upToDate 'com.google.guava:guava:16.0-rc1'
      exceedLatest 'com.google.guava:guava:99.0-SNAPSHOT'
    }

    when:
    def reporter = evaluate(project)
    reporter.write()

    then:
    with(reporter) {
      unresolved.isEmpty()
      upgradeVersions.isEmpty()
      upToDateVersions.get(['group': 'com.google.guava', 'name': 'guava']).getVersion().equals('16.0-rc1')
      downgradeVersions.get(['group': 'com.google.guava', 'name': 'guava']).getVersion().equals('99.0-SNAPSHOT')
    }
  }

  def 'Single project with annotation processor'() {
    given:
    def project = singleProject()
    project.plugins.apply('java')
    addRepositoryTo(project)
    project.dependencies {
      annotationProcessor 'com.google.guava:guava:99.0-SNAPSHOT'
    }

    when:
    def reporter = evaluate(project)
    reporter.write()

    then:
    with(reporter) {
      unresolved.isEmpty()
      upgradeVersions.isEmpty()
      upToDateVersions.isEmpty()
      downgradeVersions[['group': 'com.google.guava', 'name': 'guava']].getVersion() == '99.0-SNAPSHOT'
    }
  }

  def 'Single project with component selection rule'() {
    given:
    def project = new ProjectBuilder().withName('single').build()
    addRepositoryTo(project)
    project.configurations {
      release
      all {
        resolutionStrategy {
          componentSelection {
            all { ComponentSelection selection ->
              if (selection.candidate.version.contains('rc')) {
                selection.reject("Release candidate")
              }
            }
          }
        }
      }
    }
    project.dependencies {
      release 'com.google.guava:guava:15.0'
    }

    when:
    def reporter = evaluate(project)
    reporter.write()

    then:
    with(reporter) {
      unresolved.isEmpty()
      upgradeVersions.isEmpty()
      upToDateVersions.get(['group': 'com.google.guava', 'name': 'guava']).getVersion().equals('15.0')
      downgradeVersions.isEmpty()
    }
  }

  def 'Read project url from pom'() {
    given:
    def project = new ProjectBuilder().withName('single').build()
    addRepositoryTo(project)
    project.configurations {
      compile
    }
    project.dependencies {
      compile 'backport-util-concurrent:backport-util-concurrent:3.1'
    }

    when:
    def reporter = evaluate(project)
    reporter.write()

    then:
    with(reporter) {
      projectUrls == [['group': 'backport-util-concurrent', 'name': 'backport-util-concurrent']:
                        'https://backport-jsr166.sourceforge.net/']
    }
  }

  def 'Read project url from direct parent pom'() {
    given:
    def project = new ProjectBuilder().withName('single').build()
    addRepositoryTo(project)
    project.configurations {
      compile
    }
    project.dependencies {
      compile 'com.google.inject:guice:3.0'
    }

    when:
    def reporter = evaluate(project)
    reporter.write()

    then:
    with(reporter) {
      projectUrls == [['group': 'com.google.inject', 'name': 'guice']:
                        'https://code.google.com/p/google-guice/']
    }
  }

  def 'Read project url from indirect parent pom'() {
    given:
    def project = new ProjectBuilder().withName('single').build()
    addRepositoryTo(project)
    project.configurations {
      compile
    }
    project.dependencies {
      compile 'com.google.inject.extensions:guice-multibindings:3.0'
    }

    when:
    def reporter = evaluate(project)
    reporter.write()

    then:
    with(reporter) {
      unresolved.isEmpty()
      upgradeVersions.isEmpty()
      projectUrls == [['group': 'com.google.inject.extensions', 'name': 'guice-multibindings']:
                        'https://code.google.com/p/google-guice/']
      downgradeVersions.isEmpty()
    }
  }

  def 'Project url tag in pom does not exist'() {
    given:
    def project = new ProjectBuilder().withName('single').build()
    addRepositoryTo(project)
    project.configurations {
      compile
    }
    project.dependencies {
      compile 'backport-util-concurrent:backport-util-concurrent-java12:3.1'
    }

    when:
    def reporter = evaluate(project)
    reporter.write()

    then:
    with(reporter) {
      projectUrls.isEmpty()
    }
  }

  def 'Project url of sonatype oss-parent is ignored'() {
    given:
    def project = new ProjectBuilder().withName('single').build()
    addRepositoryTo(project)
    project.configurations {
      compile
    }
    project.dependencies {
      compile 'com.google.guava:guava:15.0'
    }

    when:
    def reporter = evaluate(project)
    reporter.write()

    then:
    with(reporter) {
      projectUrls.isEmpty()
    }
  }

  def 'Constructor takes gradle release channel'() {
    given:
    def project = new ProjectBuilder().withName('single').build()
    addRepositoryTo(project)
    project.configurations {
      compile
    }
    project.dependencies {
      compile 'com.google.guava:guava:15.0'
    }

    when:
    def reporter = evaluate(project, 'milestone', 'plain', 'build', null, null, true, CURRENT.id)
    reporter.write()

    then:
    with(reporter) {
      gradleReleaseChannel.equals(CURRENT.id)
    }
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/285')
  def 'checkForGradleUpdate=false does not cause an NPE'() {
    given:
    def project = new ProjectBuilder().withName('single').build()
    addDependenciesTo(project)

    when:
    def reporter = evaluate(project, 'milestone', 'plain', 'build', null, null, false)
    reporter.write()

    then:
    noExceptionThrown()
    with(reporter) {
      unresolved.size() == 8
      upgradeVersions.isEmpty()
      upToDateVersions.isEmpty()
      downgradeVersions.isEmpty()
    }
  }

  private static def singleProject() {
    return new ProjectBuilder().withName('single').build()
  }

  private static def multiProject() {
    def rootProject = new ProjectBuilder().withName('root').build()
    def childProject = new ProjectBuilder().withName('child').withParent(rootProject).build()
    def leafProject = new ProjectBuilder().withName('leaf').withParent(childProject).build()
    [rootProject, childProject, leafProject]
  }

  private static def evaluate(project, revision = 'milestone', outputFormatter = 'plain',
                              outputDir = 'build', resolutionStrategy = null, reportfileName = null, checkForGradleUpdate = true, gradleReleaseChannel = RELEASE_CANDIDATE.id) {
    new DependencyUpdates(project, resolutionStrategy, revision, outputFormatter, outputDir, reportfileName, checkForGradleUpdate, gradleReleaseChannel).run()
  }

  private void addRepositoryTo(project) {
    def localMavenRepo = getClass().getResource('/maven/')
    project.repositories {
      maven {
        url localMavenRepo.toURI()
      }
    }
  }

  private static void addBadRepositoryTo(project) {
    project.repositories {
      maven { url = 'https://www.example.com' }
    }
  }

  private static void addDependenciesTo(project) {
    project.configurations {
      upToDate
      exceedLatest
      upgradesFound
      unresolvable
    }
    project.dependencies {
      upToDate('backport-util-concurrent:backport-util-concurrent:3.1') { because 'I said so' }
      upToDate('backport-util-concurrent:backport-util-concurrent-java12:3.1')
      exceedLatest('com.google.guava:guava:99.0-SNAPSHOT') { because 'I know the future' }
      exceedLatest('com.google.guava:guava-tests:99.0-SNAPSHOT')
      upgradesFound('com.google.inject:guice:2.0') { because 'That\'s just the way it is' }
      upgradesFound('com.google.inject.extensions:guice-multibindings:2.0')
      unresolvable('com.github.ben-manes:unresolvable:1.0') { because 'Life is hard' }
      unresolvable('com.github.ben-manes:unresolvable2:1.0')
    }
  }

  private static void checkUnresolvedVersions(def reporter) {
    Map<Map<String, String>, ModuleVersionSelector> unresolvedMap = reporter.unresolved
      .collect { it.selector }.collectEntries { dependency ->
      [['group': dependency.group, 'name': dependency.name]: dependency]
    }
    assert reporter.unresolved.size() == 2
    assert unresolvedMap
      .get(['group': 'com.github.ben-manes', 'name': 'unresolvable'])
      .getVersion() == '+'
    assert reporter.currentVersions
      .get(['group': 'com.github.ben-manes', 'name': 'unresolvable'])
      .getUserReason() == 'Life is hard'
    assert unresolvedMap
      .get(['group': 'com.github.ben-manes', 'name': 'unresolvable2'])
      .getVersion() == '+'
  }

  private static void checkUpgradeVersions(def reporter) {
    assert reporter.upgradeVersions.size() == 2
    assert reporter.upgradeVersions
      .get(['group': 'com.google.inject', 'name': 'guice'])
      .getVersion() == '2.0'
    assert reporter.upgradeVersions
      .get(['group': 'com.google.inject', 'name': 'guice'])
      .getUserReason() == 'That\'s just the way it is'
    assert reporter.upgradeVersions
      .get(['group': 'com.google.inject.extensions', 'name': 'guice-multibindings'])
      .getVersion() == '2.0'
  }

  private static void checkUpToDateVersions(def reporter) {
    assert reporter.upToDateVersions.size() == 2
    assert reporter.upToDateVersions
      .get(['group': 'backport-util-concurrent', 'name': 'backport-util-concurrent'])
      .getVersion() == '3.1'
    assert reporter.upToDateVersions
      .get(['group': 'backport-util-concurrent', 'name': 'backport-util-concurrent'])
      .getUserReason() == 'I said so'
    assert reporter.upToDateVersions
      .get(['group': 'backport-util-concurrent', 'name': 'backport-util-concurrent-java12'])
      .getVersion() == '3.1'
  }

  private static void checkDowngradeVersions(def reporter) {
    assert reporter.downgradeVersions.size() == 2
    assert reporter.downgradeVersions
      .get(['group': 'com.google.guava', 'name': 'guava'])
      .getVersion() == '99.0-SNAPSHOT'
    assert reporter.downgradeVersions
      .get(['group': 'com.google.guava', 'name': 'guava'])
      .getUserReason() == 'I know the future'
    assert reporter.downgradeVersions
      .get(['group': 'com.google.guava', 'name': 'guava-tests'])
      .getVersion() == '99.0-SNAPSHOT'
  }
}
