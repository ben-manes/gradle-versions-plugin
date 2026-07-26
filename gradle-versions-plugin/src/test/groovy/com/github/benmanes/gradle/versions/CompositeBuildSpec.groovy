package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Unroll

@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1004')
final class CompositeBuildSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String classpathString
  private String mavenRepoUrl

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.getResource('plugin-classpath.txt')
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        'Did not find plugin classpath resource, run `testClasses` build task.')
    }
    classpathString = pluginClasspathResource.readLines()
      .collect { it.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(', ')
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  private def run(String... arguments) {
    return GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  private void includedBuild(String name, String buildScript = '') {
    testProjectDir.newFolder(name)
    testProjectDir.newFile("$name/settings.gradle") << "rootProject.name = '$name'"
    testProjectDir.newFile("$name/build.gradle") << buildScript
  }

  def 'Reports the updates of a build that includes another build'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild('child')

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  def 'Aggregates the updates of every project in a build that includes another build'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        include 'app', 'lib'
        includeBuild 'child'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          apply plugin: 'java'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()
    includedBuild('child')

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
  }

  def 'Reports the updates of a build that consumes the build it includes'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.example:child:1.0'
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild(
      'child',
      """
        plugins {
          id 'java-library'
        }

        group = 'com.example'
        version = '1.0'
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Unroll
  @Issue([
    'https://github.com/ben-manes/gradle-versions-plugin/issues/781',
    'https://github.com/ben-manes/gradle-versions-plugin/issues/1004',
  ])
  def 'Reports the updates of a composite build using strict locking activated by #activation'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencyLocking {
          lockMode = LockMode.STRICT
        }

        ${script}

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild('child')

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')

    where:
    activation              | script
    'a top-level hook'      | 'configurations.all { resolutionStrategy.activateDependencyLocking() }'
    'an afterEvaluate hook' | 'afterEvaluate { configurations.all { resolutionStrategy.activateDependencyLocking() } }'
  }

  // The composite computes its task graph under configure on demand before projectsEvaluated
  // fires, so no lifecycle callback can mutate the results strategy in time.
  private void compositeUsingConfigureOnDemand() {
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild('child')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1006')
  def 'Reports the updates of a composite build with configure on demand'() {
    given:
    compositeUsingConfigureOnDemand()

    when:
    def result = run(':dependencyUpdates', '--configure-on-demand')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  // Gradle 9 requires JVM 17. The guard that rejects the mutation is worded differently there,
  // and only this combination matches a build that sets all three properties in gradle.properties.
  @Requires({ jvm.java17Compatible })
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1006')
  def 'Reports the updates of a composite build with configure on demand in parallel'() {
    given:
    compositeUsingConfigureOnDemand()

    when:
    def result = GradleRunner.create()
      .withGradleVersion('9.6.1')
      .withProjectDir(testProjectDir.root)
      .withArguments(':dependencyUpdates', '--configure-on-demand', '--parallel',
        '--configuration-cache')
      .withPluginClasspath()
      .build()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  def 'Reports the updates of an included build from the including build'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') << ''
    includedBuild(
      'child',
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java-library'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.guava:guava:15.0'
        }
      """.stripIndent(),
    )

    when:
    def result = run(':child:dependencyUpdates')

    then:
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
  }

  def 'Reports the updates of both builds when each applies the plugin'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild(
      'child',
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java-library'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.guava:guava:15.0'
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates', ':child:dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
  }

  def 'Reuses the configuration cache across runs of a composite build'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild('child')

    when:
    run('dependencyUpdates', '--configuration-cache')
    def result = run('dependencyUpdates', '--configuration-cache')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('Configuration cache entry reused.')
    // The report must survive the cache hit, not just the task outcome.
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  // Gradle 9 requires JVM 17.
  @Requires({ jvm.java17Compatible })
  @Unroll
  def 'Reports the updates of a composite build on Gradle #gradleVersion'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    includedBuild('child')

    when:
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')

    where:
    gradleVersion << ['9.0.0', '9.6.1']
  }
}
