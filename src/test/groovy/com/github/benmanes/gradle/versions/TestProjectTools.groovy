package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.DependencyUpdates
import org.gradle.api.logging.LogLevel
import org.gradle.testfixtures.ProjectBuilder

class TestProjectTools {

  static def singleProject() {
    return new ProjectBuilder().withName('single').build()
  }

  static def multiProject() {
    def rootProject = new ProjectBuilder().withName('root').build()
    def childProject = new ProjectBuilder().withName('child').withParent(rootProject).build()
    def leafProject = new ProjectBuilder().withName('leaf').withParent(childProject).build()
    [rootProject, childProject, leafProject]
  }

  static void addRepositoryTo(project) {
    def localMavenRepo = getClass().getResource('/maven/')
    project.repositories {
      maven {
        url localMavenRepo.toURI()
      }
    }
  }

  static void addBadRepositoryTo(project) {
    project.repositories {
      maven { url = 'http://www.example.com' }
    }
  }

  static void addDependenciesTo(project) {
    project.configurations {
      upToDate
      exceedLatest
      upgradesFound
      unresolvable
    }
    project.dependencies {
      upToDate('backport-util-concurrent:backport-util-concurrent:3.1'){because 'I said so'}
      upToDate('backport-util-concurrent:backport-util-concurrent-java12:3.1')
      exceedLatest('com.google.guava:guava:99.0-SNAPSHOT'){because 'I know the future'}
      exceedLatest('com.google.guava:guava-tests:99.0-SNAPSHOT')
      upgradesFound('com.google.inject:guice:2.0'){because 'That\'s just the way it is'}
      upgradesFound('com.google.inject.extensions:guice-multibindings:2.0')
      unresolvable('com.github.ben-manes:unresolvable:1.0'){because 'Life is hard'}
      unresolvable('com.github.ben-manes:unresolvable2:1.0')
    }
  }

  static def evaluate(project, revision = 'milestone', outputFormatter = 'plain',
                              outputDir = 'build', resolutionStrategy = null) {
    new DependencyUpdates(project, resolutionStrategy, revision, outputFormatter, outputDir).run()
  }
}
