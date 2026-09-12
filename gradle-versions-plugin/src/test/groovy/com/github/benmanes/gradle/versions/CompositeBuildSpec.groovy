package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import groovy.xml.XmlParser
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.IgnoreIf
import spock.lang.Issue
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Unroll

@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1004')
final class CompositeBuildSpec extends Specification {
  /** A Kotlin lambda calling a function the build script declares, so that it holds the script. */
  private static final String REJECTOR = '{ v: String -> v.isRejected() }'

  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String classpathString
  private String mavenRepoUrl

  def 'setup'() {
    classpathString = PluginClasspath.asFilesArgument()
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
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
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
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1095')
  def 'Reports the project url of a module #scenario'() {
    given:
    testProjectDir.newFile('settings.gradle') << settings
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
          implementation 'com.example:interpolated-url:1.0'
        }
      """.stripIndent()
    includedBuild(
      'interpolated-url',
      """
        plugins {
          id 'java-library'
        }

        group = 'com.example'
        version = '1.0'
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=json')
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    def dependency = jsonReport.current.dependencies.find { it.name == 'interpolated-url' }
    dependency != null
    dependency.projectUrl == projectUrl

    where:
    scenario                        | settings                         | projectUrl
    'a repository publishes'        | ''                               | 'https://example.com/com.example/interpolated-url/1.0'
    'an included build substitutes' | "includeBuild 'interpolated-url'" | null
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
      .withGradleVersion(GradleVersions.CURRENT)
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
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
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
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1048')
  def 'Aggregates an included build named by its coordinates that publishes by fallback'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }
      """.stripIndent()
    // Substituted onto the included build's project, so the aggregation has a module dependency
    // rather than a project one, and its artifact is added late enough to leave the project without
    // a variant of its own to be selected by.
    includedBuild(
      'child',
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        configurations.maybeCreate('default')
        afterEvaluate {
          artifacts.add('default', file('child.jar'))
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.example:jvm-library:1.0'
        }
      """.stripIndent(),
    )
    testProjectDir.newFile('child/child.jar')

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.example:jvm-library [1.0 -> 2.0]')
  }

  def 'Aggregates every project of an included build named by its coordinates'() {
    given:
    aggregatedIncludedBuild("dependencyUpdatesAggregation 'com.example:child:1.0'")

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
    result.output.contains('com.example:jvm-library [1.0 -> 2.0]')
  }

  def 'Aggregates what a named project of an included build aggregates, not its whole build'() {
    given:
    aggregatedIncludedBuild("dependencyUpdatesAggregation 'com.example:sub:1.0'")

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    // ':child:sub' declares this one, and aggregates no project of its own.
    result.output.contains('com.example:jvm-library [1.0 -> 2.0]')
    // The child's root project declares this one. An entry for a subproject leaves the root out,
    // where an entry for the root merges the subproject as well: a declaration reaches what that
    // project aggregates, and only a build's root project aggregates the whole build.
    !result.output.contains('com.google.guava')
  }

  def 'Aggregates an included build from a Kotlin build script'() {
    given: "the README's Kotlin snippet, where the typed accessor exists only if the plugin created the configuration first"
    testProjectDir.newFile('settings.gradle.kts') << 'includeBuild("child")'
    testProjectDir.newFile('build.gradle.kts') <<
      """
        plugins {
          id("io.github.ben-manes.versions")
        }

        dependencies {
          dependencyUpdatesAggregation("com.example:child:1.0")
        }
      """.stripIndent()
    coordinatedChild()

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
  }

  def 'Aggregates an included build from a report the settings plugin registered'() {
    given: 'the aggregating build applies from its settings, while the included build applies per project'
    testProjectDir.newFile('settings.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions.settings'
        }

        includeBuild 'child'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }
      """.stripIndent()
    coordinatedChild()

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
  }

  def "Applies the aggregating report's settings to an included build with none of its own"() {
    given: 'a child declaring a module with a pre-release string as its ceiling, and no rules of its own'
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
          revision = 'release'
          rejectVersionIf {
            candidate.version.contains('-')
          }
        }
      """.stripIndent()
    coordinatedChild('com.probe:unstable-ceiling:1.0')

    when:
    def result = run('dependencyUpdates')

    then: "the child's row is held to the report's own rule, not to the defaults it was resolved under"
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.probe:unstable-ceiling [1.0 -> 2.0]')
  }

  /** Writes an included build that publishes by the coordinates an aggregation entry declares. */
  private void coordinatedChild(String dependency = 'com.google.guava:guava:15.0') {
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'\n"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool '${dependency}'
        }
      """.stripIndent()
  }

  def 'Reports a project of an included build once when named with the build it belongs to'() {
    given:
    aggregatedIncludedBuild(
      """
        dependencyUpdatesAggregation 'com.example:child:1.0'
        dependencyUpdatesAggregation 'com.example:sub:1.0'
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.count('com.example:jvm-library [1.0 -> 2.0]') == 1
  }

  // The results are published as the graph edges rather than as the files the aggregate collected,
  // which Gradle 9 will not resolve for a consumer without a lock on the included build.
  @IgnoreIf({ !GradleVersions.drivenBy(data.gradleVersion) })
  @Unroll
  def 'Aggregates every project of an included build on Gradle #gradleVersion'() {
    given:
    aggregatedIncludedBuild("dependencyUpdatesAggregation 'com.example:child:1.0'")

    when:
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
    result.output.contains('com.example:jvm-library [1.0 -> 2.0]')

    where:
    gradleVersion << ['9.0.0', '9.6.1']
  }

  def 'Aggregates two projects of an included build with the same group and name'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') <<
      """
        rootProject.name = 'child'
        include 'a:common', 'b:common'
      """.stripIndent()
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        allprojects {
          group = 'com.example'
          version = '1.0'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }

          configurations.create('tool') {
            canBeResolved = true
            canBeConsumed = false
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child', 'a', 'common')
    testProjectDir.newFile('child/a/common/build.gradle') <<
      """
        dependencies {
          tool 'com.google.guava:guava:15.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('child', 'b', 'common')
    testProjectDir.newFile('child/b/common/build.gradle') <<
      """
        dependencies {
          tool 'com.example:jvm-library:1.0'
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
    result.output.contains('com.example:jvm-library [1.0 -> 2.0]')
  }

  def 'Aggregates an included build that writes its results to a custom build directory'() {
    given:
    aggregatedIncludedBuild(
      "dependencyUpdatesAggregation 'com.example:child:1.0'",
      'layout.buildDirectory = layout.projectDirectory.dir("out-of-tree")',
    )

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
    result.output.contains('com.example:jvm-library [1.0 -> 2.0]')
    // Asserted so that the case still distinguishes if the override ever stops taking effect: the
    // results are resolved as artifacts, so a build directory the aggregator cannot guess is moot.
    new File(testProjectDir.root, 'child/out-of-tree').directory
    !new File(testProjectDir.root, 'child/build').exists()
  }

  /** Writes a build that aggregates an included build of two projects, each with an update. */
  private void aggregatedIncludedBuild(String aggregated, String childSettings = '') {
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          ${aggregated}
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') <<
      """
        rootProject.name = 'child'
        include 'sub'
      """.stripIndent()
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        allprojects {
          group = 'com.example'
          version = '1.0'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }

          configurations.create('tool') {
            canBeResolved = true
            canBeConsumed = false
          }

          ${childSettings}
        }

        dependencies {
          tool 'com.google.guava:guava:15.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('child', 'sub')
    testProjectDir.newFile('child/sub/build.gradle') <<
      """
        dependencies {
          tool 'com.example:jvm-library:1.0'
        }
      """.stripIndent()
  }

  def 'Reports the platform that an included build platform imports'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'platforms'"
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
          implementation platform('com.example:platforms:1.0')
          implementation 'com.google.inject:guice'
          implementation 'com.example:bom-consumer:1.0'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    includedBuild(
      'platforms',
      """
        plugins {
          id 'java-platform'
        }

        group = 'com.example'
        version = '1.0'

        javaPlatform {
          allowDependencies()
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.example:external-bom [1.0 -> 2.0]')
    result.output.contains('imported by the platform :platforms\n')
    // A platform that only a library's metadata drags in is not one the build imported.
    !result.output.contains('com.example:dragged-bom')
  }

  def 'Bounds the reported platform at the version the platform project declares'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'platforms'"
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
          implementation platform('com.example:platforms:1.0')
          implementation 'com.google.inject:guice'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
          rejectVersionIf {
            !satisfiesDeclaredBound
          }
        }
      """.stripIndent()
    includedBuild(
      'platforms',
      """
        plugins {
          id 'java-platform'
        }

        group = 'com.example'
        version = '1.0'

        javaPlatform {
          allowDependencies()
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api(platform('com.example:external-bom')) {
            version {
              strictly '[1.0, 2.0['
            }
          }
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.example:external-bom:1.0')
    // 2.0 exists, but the platform project's declaration excludes it and the bound rides along.
    !result.output.contains('com.example:external-bom [1.0 -> 2.0]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Reports the imported platform when every declared module is versioned'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'platforms'"
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
          implementation platform('com.example:platforms:1.0')
          implementation 'com.google.inject:guice:2.0'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    includedBuild(
      'platforms',
      """
        plugins {
          id 'java-platform'
        }

        group = 'com.example'
        version = '1.0'

        javaPlatform {
          allowDependencies()
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.example:external-bom [1.0 -> 2.0]')
    result.output.contains('imported by the platform :platforms\n')
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Prints the importing platform in the file reports'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'platforms'"
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
          implementation platform('com.example:platforms:1.0')
          implementation 'com.google.inject:guice'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    includedBuild(
      'platforms',
      """
        plugins {
          id 'java-platform'
        }

        group = 'com.example'
        version = '1.0'

        javaPlatform {
          allowDependencies()
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=json,xml')
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
    def xmlReport = new XmlParser()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.xml'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    def bom = jsonReport.outdated.dependencies.find { it.name == 'external-bom' }
    bom.platformProjects == [':platforms']
    def guice = jsonReport.current.dependencies.find { it.name == 'guice' }
    !guice.containsKey('platformProjects')
    def bomElement = xmlReport.outdated.dependencies.outdatedDependency.find {
      it.name.text() == 'external-bom'
    }
    bomElement.platformProjects.platformProject*.text() == [':platforms']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Aggregates the importers of a platform that two projects import'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        include 'app', 'lib', 'platform-a', 'platform-b'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    ['platform-a', 'platform-b'].each { name ->
      testProjectDir.newFolder(name)
      testProjectDir.newFile("$name/build.gradle") <<
        """
          plugins { id 'java-platform' }
          javaPlatform {
            allowDependencies()
          }
          dependencies {
            api platform('com.example:external-bom:1.0')
          }
        """.stripIndent()
    }
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation platform(project(':platform-a'))
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation platform(project(':platform-b'))
          implementation 'com.google.inject:guice'
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=json', '--no-parallel')
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    def boms = jsonReport.outdated.dependencies.findAll { it.name == 'external-bom' }
    boms.size() == 1
    boms[0].platformProjects == [':platform-a', ':platform-b']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Reports the platform imported by the only dependency the build declares'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        include 'app', 'platform-a'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    testProjectDir.newFolder('platform-a')
    testProjectDir.newFile('platform-a/build.gradle') <<
      """
        plugins { id 'java-platform' }
        javaPlatform {
          allowDependencies()
        }
        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation platform(project(':platform-a'))
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=json', '--no-parallel')
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    def boms = jsonReport.outdated.dependencies.findAll { it.name == 'external-bom' }
    boms.size() == 1
    boms[0].platformProjects == [':platform-a']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Reports the platform an enforcedPlatform declaration imports'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        include 'app', 'platform-a'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    testProjectDir.newFolder('platform-a')
    testProjectDir.newFile('platform-a/build.gradle') <<
      """
        plugins { id 'java-platform' }
        javaPlatform {
          allowDependencies()
        }
        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation enforcedPlatform(project(':platform-a'))
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=json', '--no-parallel')
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    def boms = jsonReport.outdated.dependencies.findAll { it.name == 'external-bom' }
    boms.size() == 1
    boms[0].platformProjects == [':platform-a']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Skips the platform scan when the imported platform cannot be resolved'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'platforms'"
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
          implementation platform('com.example:platforms:1.0')
          implementation 'com.google.inject:guice'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    includedBuild(
      'platforms',
      """
        plugins {
          id 'java-platform'
        }

        group = 'com.example'
        version = '1.0'

        javaPlatform {
          allowDependencies()
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api platform('com.example:missing-bom:9.9')
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains('missing-bom')
    // Disabling the platform scan entirely would also satisfy the absence above, so pin that the
    // configuration's other dependency still made it into the report.
    result.output.contains('com.google.inject:guice')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Omits the platform mark when another project declares the imported platform'() {
    given:
    testProjectDir.newFile('settings.gradle') <<
      """
        include 'app', 'lib', 'platform-b'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    testProjectDir.newFolder('platform-b')
    testProjectDir.newFile('platform-b/build.gradle') <<
      """
        plugins { id 'java-platform' }
        javaPlatform {
          allowDependencies()
        }
        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation platform('com.example:external-bom:1.0')
        }
      """.stripIndent()
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation platform(project(':platform-b'))
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=json', '--info', '--no-parallel')
    def jsonReport = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    def boms = jsonReport.outdated.dependencies.findAll { it.name == 'external-bom' }
    boms.size() == 1
    !boms[0].containsKey('platformProjects')
    !result.output.contains('imported by the platform')
    result.output.contains(
      "A project outside com.example:external-bom's platform importers declares it, " +
        'so the platform mark is withheld')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Prints the platform importer by its build-tree path when the report runs in an included build'() {
    given: 'the composite runs the aggregating task from inside the included build, not the root'
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') << ''
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') <<
      """
        rootProject.name = 'child'
        include 'app', 'platform-a'
      """.stripIndent()
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        tasks.dependencyUpdates {
          checkConstraints = true
        }

        allprojects {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child/platform-a')
    testProjectDir.newFile('child/platform-a/build.gradle') <<
      """
        plugins { id 'java-platform' }
        javaPlatform {
          allowDependencies()
        }
        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent()
    testProjectDir.newFolder('child/app')
    testProjectDir.newFile('child/app/build.gradle') <<
      """
        apply plugin: 'java-library'
        dependencies {
          implementation platform(project(':platform-a'))
        }
      """.stripIndent()

    when:
    def result = run(':child:dependencyUpdates')

    then: 'the importer is named by its path in the build tree, not platform-a\'s path within child'
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.example:external-bom [1.0 -> 2.0]')
    result.output.contains('imported by the platform :child:platform-a\n')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'Merges an imported platform with the version a library elsewhere drags in'() {
    given: 'a library drags a newer version of the same bom the platform states'
    testProjectDir.newFile('settings.gradle') << "include 'platform-a'"
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
          implementation platform(project(':platform-a'))
          implementation 'com.example:external-bom-consumer:1.0'
          implementation 'com.google.inject:guice'
        }

        tasks.dependencyUpdates {
          checkConstraints = true
        }
      """.stripIndent()
    testProjectDir.newFolder('platform-a')
    testProjectDir.newFile('platform-a/build.gradle') <<
      """
        plugins { id 'java-platform' }
        javaPlatform {
          allowDependencies()
        }
        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }
        dependencies {
          api platform('com.example:external-bom:1.0')
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates')

    then: 'one merged row shows the platform-stated version and the update behind the drag'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.example:external-bom [1.0 -> 2.0]')
    result.output.contains('imported by the platform :platform-a\n')
    !result.output.contains('com.example:external-bom:2.0')
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

  @IgnoreIf({ !GradleVersions.drivenBy(data.gradleVersion) })
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
    gradleVersion << ['9.0.0', GradleVersions.CURRENT]
  }

  // The measured #801 trigger: a withModule id missing its ':name' half.
  private static String throwingStrategy() {
    return '''
      dependencyUpdates.resolutionStrategy {
        componentSelection { rules ->
          rules.withModule('com.google.guava') { }
        }
      }
      '''.stripIndent()
  }

  // Gradle 9 requires JVM 17.
  @Requires({ jvm.java17Compatible })
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/801')
  def 'Surfaces the skipped configurations of both builds on Gradle 9'() {
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

        ${throwingStrategy()}
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

        ${throwingStrategy()}
      """.stripIndent(),
    )

    when:
    def result = GradleRunner.create()
      .withGradleVersion(GradleVersions.CURRENT)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', ':child:dependencyUpdates',
        '-DoutputFormatter=plain,json')
      .withPluginClasspath()
      .build()
    def including = report('')
    def included = report('child/')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    result.output.contains('Failed to inspect the dependencies of the following configurations')

    // Each build reports on its own projects, printed as the path they have in the build tree so
    // that the included build's root is told apart from the including build's.
    [including, included].every { it.skipped.count > 0 }
    [including, included].every { it.skipped.configurations*.name.contains('compileClasspath') }
    [including, included].every { it.skipped.configurations*.name.unique().size() == it.skipped.count }
    including.skipped.count + included.skipped.count == 13
    including.skipped.configurations.every { it.project == ':' }
    included.skipped.configurations.every { it.project == ':child' }

    // The warning above the section shows the same project the section does, rather than printing
    // both builds' roots under the ':' that each build uses for its own.
    result.output.contains('in :child:')
    result.output.count('in root project:') == 1
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1075')
  def 'Prints an included build that aggregates its own projects by its build tree path'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') << ''
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') <<
      """
        rootProject.name = 'child'
        include 'alpha', 'beta'
      """.stripIndent()
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        allprojects {
          apply plugin: 'java'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child', 'alpha')
    testProjectDir.newFile('child/alpha/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
    testProjectDir.newFolder('child', 'beta')
    testProjectDir.newFile('child/beta/build.gradle') <<
      """
        dependencies {
          implementation 'com.google.guava:guava:15.0'
        }
      """.stripIndent()

    when:
    def result = run(':child:dependencyUpdates')

    then:
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    result.output.contains(':child Project Dependency Updates')
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0]')

    // The projects the completeness check expects are named the way the partial results stamp
    // them, so an included build that aggregates does not report its own projects as absent.
    !result.output.contains('The dependency updates report is missing')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1075')
  def 'Prints the projects that declare a divergent version by their build tree paths'() {
    given:
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
          tool 'com.example:jvm-library:2.0'
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

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        configurations.maybeCreate('default')
        afterEvaluate {
          artifacts.add('default', file('child.jar'))
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.example:jvm-library:1.0'
        }
      """.stripIndent(),
    )
    testProjectDir.newFile('child/child.jar')

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=plain,json')
    def json = report('')

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS

    // The path of each build's own root is ':', which read as one project and left out the
    // divergence altogether rather than printing the two that disagree.
    json.outdated.dependencies.find { it.name == 'jvm-library' }.projects == [':child']
    json.current.dependencies.find { it.name == 'jvm-library' }.projects == [':']
    result.output.contains("declared in the 'tool' configuration in root project")
    result.output.contains("declared in the 'tool' configuration in :child")
  }

  private def report(String path) {
    return new JsonSlurper()
      .parse(new File(testProjectDir.root, "${path}build/dependencyUpdates/report.json"))
  }

  private void ruledComposite(
    String outerBody =
      """
        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            candidate.version == '3.1'
          }
        }
      """) {
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        ${outerBody}
      """.stripIndent()
    ruledChild()
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "An aggregation entry does not reach the build that its declared build includes"() {
    given: "an entry for the child alone at the root, and an entry for the grandchild in the child"
    nestedComposite(false)

    when:
    def result = run('dependencyUpdates')

    then: "the child build's projects are merged from the entry, and nothing past that build's boundary"
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice')
    !result.output.contains('com.google.guava:guava')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "An aggregation entry declares an included build of an included build"() {
    given: 'the same tree, with the root naming the grandchild build as well'
    nestedComposite(true)

    when:
    def result = run('dependencyUpdates')

    then: 'a build anywhere in the tree is reached by naming it, however deeply it is included'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice')
    result.output.contains('com.google.guava:guava')
  }

  /** A root including a child that itself includes and aggregates a grandchild. */
  private void nestedComposite(boolean namesGrandchild) {
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
          ${namesGrandchild ? "dependencyUpdatesAggregation 'com.example:grandchild:1.0'" : ''}
        }
      """.stripIndent()

    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') <<
      "rootProject.name = 'child'\nincludeBuild 'grandchild'"
    testProjectDir.newFile('child/build.gradle') << aggregatingBuild('child', 'grandchild',
      "com.google.inject:guice:2.0")

    testProjectDir.newFolder('child', 'grandchild')
    testProjectDir.newFile('child/grandchild/settings.gradle') << "rootProject.name = 'grandchild'"
    testProjectDir.newFile('child/grandchild/build.gradle') << aggregatingBuild('grandchild', null,
      "com.google.guava:guava:15.0")
  }

  /** A build that applies the plugin, declares [dependency], and aggregates [aggregated] if named. */
  private String aggregatingBuild(String name, String aggregated, String dependency) {
    return """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool '${dependency}'
          ${aggregated == null ? '' : "dependencyUpdatesAggregation 'com.example:${aggregated}:1.0'"}
        }
      """.stripIndent()
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A Kotlin rule calling a buildSrc helper keeps the configuration cache"() {
    given: 'the helper moved out of the build script, which is the remedy printed in the warning'
    kotlinRuledComposite('candidate.version.isRejected()')
    testProjectDir.newFolder('buildSrc', 'src', 'main', 'java')
    testProjectDir.newFile('buildSrc/src/main/java/Stability.java') <<
      """
        public final class Stability {
          public static boolean isRejected(String version) {
            return "3.1".equals(version);
          }
        }
      """.stripIndent()
    def script = new File(testProjectDir.root, 'build.gradle.kts')
    script.text = script.text
      .replace('fun String.isRejected(): Boolean = this == "3.1"', '')
      .replace('candidate.version.isRejected()', 'Stability.isRejected(candidate.version)')

    when:
    def store = run('dependencyUpdates', '--configuration-cache', '--no-parallel')
    def hit = run('dependencyUpdates', '--configuration-cache', '--no-parallel')

    then: 'the rule reaches a compiled class rather than the script, so the entry is stored and reused'
    store.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    !store.output.contains('Configuration cache entry discarded')
    hit.output.contains('Reusing configuration cache')
    hit.output.contains('com.google.inject:guice [2.0 -> 3.0]')
  }

  /**
   * The same composite with a Kotlin build script, where the rule is written as the README's recipe
   * is: a call to a function the script declares. Such a call binds the script into the lambda.
   */
  private void kotlinRuledComposite(String rule, String locals = 'val rejected = "3.1"') {
    testProjectDir.newFile('settings.gradle.kts') << 'includeBuild("child")'
    testProjectDir.newFile('build.gradle.kts') <<
      """
        import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

        buildscript {
          dependencies {
            classpath(files(${classpathString.replace("'", '"')}))
          }
        }

        apply(plugin = "io.github.ben-manes.versions")

        fun String.isRejected(): Boolean = this == "3.1"

        dependencies {
          add("dependencyUpdatesAggregation", "com.example:child:1.0")
        }

        tasks.named("dependencyUpdates", DependencyUpdatesTask::class.java) {
          ${locals}
          rejectVersionIf {
            ${rule}
          }
        }
      """.stripIndent()
    ruledChild()
  }

  private void ruledChild() {
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.google.inject:guice:2.0'
        }
      """.stripIndent()
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "An including build's rejectVersionIf governs an included build's dependency"() {
    given: "the child's own resolution accepts 3.1, but the outer's rule rejects it"
    ruledComposite()

    when:
    def result = run('dependencyUpdates')

    then: "the outer's rule reaches the merged-in row, stopping it at 3.0 rather than the child's own 3.1"
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    !result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A rejectVersionIf inherited from an ancestor project governs a merged-in row"() {
    given: "the rule is declared on the root, while the subproject is what aggregates the child"
    testProjectDir.newFile('settings.gradle') <<
      """
        include 'app'
        includeBuild 'child'
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            candidate.version == '3.1'
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }
      """.stripIndent()
    ruledChild()

    when:
    def result = run(':app:dependencyUpdates')

    then: "the subproject's report reads the same inherited chain its producers resolve under"
    result.task(':app:dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    !result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "An including build's rejectVersionIf governs a merged-in row under the configuration cache"() {
    given: "the same composite, run with the cache stored and then reused"
    ruledComposite()

    when:
    def store = run('dependencyUpdates', '--configuration-cache', '--no-parallel')
    def hit = run('dependencyUpdates', '--configuration-cache', '--no-parallel')

    then: "the rules are read from the serialized task, so both legs cap the row at 3.0"
    store.task(':dependencyUpdates').outcome == SUCCESS
    store.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    !store.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    hit.output.contains('Reusing configuration cache')
    hit.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    !hit.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A Kotlin rule calling a function its own build script declares still applies to the report"() {
    given: 'the rule written as the README recipe is, so the lambda captures the script'
    kotlinRuledComposite('candidate.version.isRejected()')

    when:
    def result = run('dependencyUpdates', '--configuration-cache', '--no-parallel')

    then: 'the rules are applied, and the entry is discarded rather than the build failing to store it'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    !result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('Configuration cache entry discarded')
    result.output.contains('rejectVersionIf')
  }

  @Unroll
  def "A Kotlin rule reaching its build script through #holder still applies to the report"() {
    given: 'the script bound into the lambda through a collection rather than through a field of it'
    kotlinRuledComposite(rule, locals)

    when:
    def result = run('dependencyUpdates', '--configuration-cache', '--no-parallel')

    then: 'the entry is discarded as it is for a rule reaching the script directly'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    !result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('reads a declaration from the build script')
    result.output.contains('Configuration cache entry discarded')

    where:
    holder             | locals                                        | rule
    'a list element'   | "val held = mutableListOf($REJECTOR)"         | 'held[0](candidate.version)'
    'an array element' | "val held = arrayOf($REJECTOR)"               | 'held[0](candidate.version)'
    'a map value'      | "val held = mutableMapOf(\"a\" to $REJECTOR)" | 'held.getValue("a")(candidate.version)'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A Kotlin rule that reaches no build script keeps the configuration cache"() {
    given: 'the same composite, with the rule written so that it reads nothing the script declares'
    kotlinRuledComposite('candidate.version == rejected')

    when:
    def store = run('dependencyUpdates', '--configuration-cache', '--no-parallel')
    def hit = run('dependencyUpdates', '--configuration-cache', '--no-parallel')

    then: 'the entry is kept, so the report a Kotlin build caches today is not given up for the case above'
    store.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    !store.output.contains('Configuration cache entry discarded')
    hit.output.contains('Reusing configuration cache')
    hit.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    !hit.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "An including build's filterDeclaredConfigurations leaves out an included build's entry"() {
    given: "the child declares guice into a 'tool' configuration only the child itself could filter"
    ruledComposite(filteringOuter())

    when:
    def result = run('dependencyUpdates')

    then: 'the merged-in row is left out by the name it shows'
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains('com.google.inject:guice')
    // The survivor proves the drop is per-entry rather than an emptied report, and that a row
    // with no configuration on it is kept by the report's filter as it is by the producer's.
    result.output.contains('com.google.guava:guava')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "An including build's filterDeclaredConfigurations leaves out a merged-in entry under the cache"() {
    given: 'the same composite, run with the cache stored and then reused'
    ruledComposite(filteringOuter())

    when:
    def store = run('dependencyUpdates', '--configuration-cache', '--no-parallel')
    def hit = run('dependencyUpdates', '--configuration-cache', '--no-parallel')

    then: 'the filter is read from the serialized task, so both legs leave the row out'
    store.task(':dependencyUpdates').outcome == SUCCESS
    !store.output.contains('com.google.inject:guice')
    store.output.contains('com.google.guava:guava')
    hit.output.contains('Reusing configuration cache')
    !hit.output.contains('com.google.inject:guice')
    hit.output.contains('com.google.guava:guava')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A Kotlin filter calling a function its own build script declares still filters the report"() {
    given: 'the filter written so that the lambda binds the script, as a rule written that way does'
    kotlinFilteringComposite()

    when:
    def result = run('dependencyUpdates', '--configuration-cache', '--no-parallel')

    then: 'the report is filtered, and the entry is discarded rather than the build failing to store it'
    result.task(':dependencyUpdates').outcome == SUCCESS
    !result.output.contains('com.google.inject:guice')
    result.output.contains('com.google.guava:guava')
    result.output.contains('Configuration cache entry discarded')
    result.output.contains('filterDeclaredConfigurations')
  }

  /** The filtering composite with a Kotlin build script, its filter reading what the script declares. */
  private void kotlinFilteringComposite() {
    testProjectDir.newFile('settings.gradle.kts') << 'includeBuild("child")'
    testProjectDir.newFile('build.gradle.kts') <<
      """
        import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
        import org.gradle.api.specs.Spec

        buildscript {
          dependencies {
            classpath(files(${classpathString.replace("'", '"')}))
          }
        }

        apply(plugin = "io.github.ben-manes.versions")
        apply(plugin = "java")

        fun String.isReported(): Boolean = this != "tool"

        repositories {
          maven {
            url = uri("${mavenRepoUrl}")
          }
        }

        dependencies {
          add("dependencyUpdatesAggregation", "com.example:child:1.0")
          add("implementation", "com.google.guava:guava:15.0")
        }

        tasks.named("dependencyUpdates", DependencyUpdatesTask::class.java) {
          filterDeclaredConfigurations = Spec<String> { it.isReported() }
        }
      """.stripIndent()
    ruledChild()
  }

  /** An aggregating build that filters by a name declared only in the build it includes. */
  private String filteringOuter() {
    return """
        apply plugin: 'java'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
          implementation 'com.google.guava:guava:15.0'
        }

        tasks.named('dependencyUpdates').configure {
          filterDeclaredConfigurations { it != 'tool' }
        }
      """
  }

  @Unroll
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A composite reports the same rows with the cache as without it, #declared"() {
    given: 'the rule and the aggregation coordinate declared in either order, one of them late'
    ruledComposite(outerBody)

    when:
    def plain = run('dependencyUpdates')
    def store = run('dependencyUpdates', '--configuration-cache', '--no-parallel')
    def hit = run('dependencyUpdates', '--configuration-cache', '--no-parallel')

    then: 'a hook that runs after the project is evaluated still reaches the report'
    [plain, store, hit].every { it.output.contains('com.google.inject:guice [2.0 -> 3.0]') }
    [plain, store, hit].every { !it.output.contains('com.google.inject:guice [2.0 -> 3.1]') }

    where:
    declared << ['the rule from a later hook', 'the coordinate from a later hook']
    outerBody << [
      '''
        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        afterEvaluate {
          tasks.named('dependencyUpdates').configure {
            rejectVersionIf {
              candidate.version == '3.1'
            }
          }
        }
      ''',
      '''
        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            candidate.version == '3.1'
          }
        }

        afterEvaluate {
          dependencies {
            dependencyUpdatesAggregation 'com.example:child:1.0'
          }
        }
      ''',
    ]
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A resolutionStrategy cleared after it was captured does not govern the report"() {
    given: 'a rule registered in the build script and cleared from a later hook'
    ruledComposite(
      '''
        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            candidate.version == '3.1'
          }
        }

        afterEvaluate {
          tasks.named('dependencyUpdates').configure {
            resolutionStrategy()
          }
        }
      ''')

    when:
    def result = run('dependencyUpdates')

    then: 'clearing the strategy clears what the report would have applied along with it'
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A warning is printed where the report cannot apply its own rules, rather than nothing quietly"() {
    given: 'a strategy reading a script object as it registers, which a serialized closure may not do'
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          resolutionStrategy {
            def owner = project.path
            it.componentSelection { rules ->
              rules.all { selection ->
                if (selection.candidate.version == '3.1') {
                  selection.reject('rejected by the test rule')
                }
              }
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.google.inject:guice:2.0'
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '--configuration-cache', '--no-parallel')

    then: 'the rows stay as their own builds resolved them, and the reason is printed'
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('Every dependency is left as the build that resolved it reported it')
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "An including build's report shows no candidate its own revision rejects"() {
    given: 'a listing with its integration version below the release the child resolved'
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            candidate.version == '3.0'
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.example:snapshot-interleaved:1.0'
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=plain,json')
    def json = report('')
    def offered = json.outdated.dependencies.find { it.name == 'snapshot-interleaved' }
    def unchanged = json.current.dependencies.find { it.name == 'snapshot-interleaved' }

    then: 'the row is reported, and never at the integration version a milestone report rejects'
    result.task(':dependencyUpdates').outcome == SUCCESS
    (offered != null) || (unchanged != null)
    offered?.available?.milestone != '2.5-SNAPSHOT'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "An included build's own report is not governed by the including build"() {
    given: "the outer's rejectVersionIf targets guice, but only when it aggregates the child"
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') << ''
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java-library'
        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          api 'com.google.inject:guice:2.0'
        }
      """.stripIndent()

    when:
    def result = run(':child:dependencyUpdates')

    then: "the child's own report is unaffected by a rule never registered in the outer"
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "The #475 snapshot exemption survives on a merged row"() {
    given: 'a snapshot-only module in the child, exempted only when the report rebuilds its own current version'
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            candidate.version == '1.5'
          }
          rejectVersionIf {
            candidate.version.endsWith('-SNAPSHOT') && candidate.version != currentVersion
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.example:snapshot-mixed:1.0-SNAPSHOT'
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=plain,json')
    def json = report('')

    then: "the outer's rule rejects the release ceiling, and its rebuilt current version exempts the snapshot below it"
    result.task(':dependencyUpdates').outcome == SUCCESS
    json.current.dependencies.find { it.name == 'snapshot-mixed' }?.version == '1.0-SNAPSHOT'
    !json.outdated.dependencies*.name.contains('snapshot-mixed')
    !json.unresolved.dependencies*.name.contains('snapshot-mixed')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A merged row's declared bound survives the rebuilt constraint"() {
    given: 'a module the child bounds only through a platform, merged into a build that applies the bound'
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            !satisfiesDeclaredBound
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java-library'
        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation platform('org.apache.logging.log4j:log4j:2.16.0')
          implementation 'org.apache.logging.log4j:log4j-core'
        }

        tasks.named('dependencyUpdates').configure {
          checkConstraints = true
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=plain,json')
    def json = report('')

    then: 'the platform bound the child recorded still caps the merged row at the platform version'
    result.task(':dependencyUpdates').outcome == SUCCESS
    json.current.dependencies.find { it.name == 'log4j-core' }?.version == '2.16.0'
    !json.outdated.dependencies*.name.contains('log4j-core')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def "An aggregator's rule drops the pre-release step of a merged row"() {
    given: "the child reports the step, having no rule of its own to drop it"
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            candidate.version.contains('-')
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.probe:unstable-ceiling:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          rejectPreReleases = false
        }
      """.stripIndent()

    when:
    def result = run('dependencyUpdates', ':child:dependencyUpdates',
      '-DoutputFormatter=plain,json', '-Drevision=release')
    def included = report('child/')
    def json = report('')

    then: "the child's own report prints the step, and the outer's rule rejects it where they merge"
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    included.outdated.dependencies.find { it.name == 'unstable-ceiling' }?.available?.release == '2.0'
    included.outdated.dependencies.find { it.name == 'unstable-ceiling' }?.available?.preRelease == '3.0-Beta1'
    json.outdated.dependencies.find { it.name == 'unstable-ceiling' }?.available?.release == '2.0'
    json.outdated.dependencies.find { it.name == 'unstable-ceiling' }?.available?.preRelease == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/440')
  def "The report's rejectPreReleases drops the step from a merged row its child prints"() {
    given: 'an outer that leaves the step out, over a child that keeps it'
    unstableCeilingComposite(
      "tool 'com.probe:unstable-ceiling:1.0'",
      '',
      """
        tasks.named('dependencyUpdates').configure {
          rejectPreReleases = true
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates', ':child:dependencyUpdates', '-DoutputFormatter=plain,json')
    def included = report('child/')
    def json = report('')

    then: "the child prints the pre-release step, and the report it is merged into leaves it out"
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.task(':child:dependencyUpdates').outcome == SUCCESS
    included.outdated.dependencies.find { it.name == 'unstable-ceiling' }?.available?.milestone == '2.0'
    included.outdated.dependencies.find { it.name == 'unstable-ceiling' }?.available?.preRelease == '3.0-Beta1'
    json.outdated.dependencies.find { it.name == 'unstable-ceiling' }?.available?.milestone == '2.0'
    json.outdated.dependencies.find { it.name == 'unstable-ceiling' }?.available?.preRelease == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/440')
  def "A merged row keeps the step its child recorded where the outer configures nothing"() {
    given: 'neither build states a setting, so both are at the defaults'
    unstableCeilingComposite()

    when:
    def result = run('dependencyUpdates', ':child:dependencyUpdates', '-DoutputFormatter=plain,json')
    def included = report('child/')
    def json = report('')

    then: 'both reports read alike, since nothing in the outer moves the row'
    result.task(':dependencyUpdates').outcome == SUCCESS
    included.outdated.dependencies.find { it.name == 'unstable-ceiling' }?.available?.milestone == '2.0'
    included.outdated.dependencies.find { it.name == 'unstable-ceiling' }?.available?.preRelease ==
      '3.0-Beta1'
    json.outdated.dependencies.find { it.name == 'unstable-ceiling' }?.available?.milestone == '2.0'
    json.outdated.dependencies.find { it.name == 'unstable-ceiling' }?.available?.preRelease ==
      '3.0-Beta1'
    result.output.contains('com.probe:unstable-ceiling [1.0 -> 2.0 -> 3.0-Beta1]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/440')
  def "The report's revision leaves out a merged step its child resolved under another"() {
    given: 'a child at the integration revision, over an outer at the default milestone'
    unstableCeilingComposite(
      "tool 'com.example:snapshot-mixed:1.5'",
      "revision = 'integration'",
      '',
    )

    when:
    def result = run('dependencyUpdates', ':child:dependencyUpdates', '-DoutputFormatter=plain,json')
    def included = report('child/')
    def json = report('')

    then: "the child reports the snapshot it resolved for"
    result.task(':dependencyUpdates').outcome == SUCCESS
    included.outdated.dependencies.find { it.name == 'snapshot-mixed' }?.available?.preRelease ==
      '2.0-SNAPSHOT'

    and: "the report it is merged into is not at a revision that accepts one"
    json.outdated.dependencies.every { it.name != 'snapshot-mixed' }
    json.current.dependencies.find { it.name == 'snapshot-mixed' }?.version == '1.5'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/440')
  def "The report's preReleaseVersionIf convention moves a merged row to its step"() {
    given: "an outer with a convention neither build's markers cover, and a child with none"
    unstableCeilingComposite(
      "tool 'com.example:prerelease-flagged:1.0'",
      '',
      """
        tasks.named('dependencyUpdates').configure {
          preReleaseVersionIf { it.endsWith('-flagged') }
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates', ':child:dependencyUpdates', '-DoutputFormatter=plain,json')
    def included = report('child/')
    def json = report('')

    then: "the convention reaches the row the child resolved without it, so the flagged version moves"
    result.task(':dependencyUpdates').outcome == SUCCESS
    included.outdated.dependencies.find { it.name == 'prerelease-flagged' }?.available?.milestone == '3.0-flagged'
    included.outdated.dependencies.find { it.name == 'prerelease-flagged' }?.available?.preRelease == null
    json.outdated.dependencies.find { it.name == 'prerelease-flagged' }?.available?.milestone == null
    json.outdated.dependencies.find { it.name == 'prerelease-flagged' }?.available?.preRelease == '3.0-flagged'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/440')
  def "The report's exemption keeps a merged row its own convention would move"() {
    given: 'an outer adding a convention and exempting the one module it would match'
    unstableCeilingComposite(
      "tool 'com.example:prerelease-flagged:1.0'",
      '',
      """
        tasks.named('dependencyUpdates').configure {
          preReleaseVersionIf { it.endsWith('-flagged') }
          exemptFromBuiltInChecksIf { candidate.module == 'prerelease-flagged' }
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=plain,json')
    def json = report('')

    then: 'the exemption is read at the report, so the merged row keeps the version the child baked'
    result.task(':dependencyUpdates').outcome == SUCCESS
    json.outdated.dependencies.find { it.name == 'prerelease-flagged' }?.available?.milestone == '3.0-flagged'
    json.outdated.dependencies.find { it.name == 'prerelease-flagged' }?.available?.preRelease == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/440')
  def "The report's own convention reaches a merged row on the cache hit"() {
    given: 'an outer that adds a convention neither build\'s markers cover, over a child with none'
    unstableCeilingComposite(
      """
        tool 'com.probe:unstable-ceiling:1.0'
        tool 'com.example:prerelease-flagged:1.0'
      """.stripIndent(),
      '',
      """
        tasks.named('dependencyUpdates').configure {
          preReleaseVersionIf { it.endsWith('-flagged') }
        }
      """.stripIndent(),
    )

    when:
    def store = run('dependencyUpdates', '--configuration-cache')
    def hit = run('dependencyUpdates', '--configuration-cache')

    then: 'the convention is read from the copy that survives the cache, so the hit matches the store'
    store.task(':dependencyUpdates').outcome == SUCCESS
    hit.output.contains('Configuration cache entry reused.')
    [store, hit].every { it.output.contains('com.probe:unstable-ceiling [1.0 -> 2.0 -> 3.0-Beta1]') }
    [store, hit].every { it.output.contains('com.example:prerelease-flagged [1.0 -> 3.0-flagged]') }
  }

  /**
   * Writes an outer that aggregates a child with no settings of its own, so a merged row carries
   * what the child's defaults produced and the outer's report is the only place its own convention,
   * exemption and rules can apply.
   */
  private void unstableCeilingComposite(
      String childDependency = "tool 'com.probe:unstable-ceiling:1.0'",
      String childConfig = '',
      String outerConfig = '') {
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
        }
        $outerConfig
      """.stripIndent()
    testProjectDir.newFolder('child')
    testProjectDir.newFile('child/settings.gradle') << "rootProject.name = 'child'"
    testProjectDir.newFile('child/build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          $childDependency
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
          $childConfig
        }
      """.stripIndent()
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "A merged row never overrules the outer's own verdict for a coordinate it declares too"() {
    given: 'both builds declare guava, and only the outer rejects the 16.0 line'
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
          tool 'com.google.guava:guava:15.0'
        }

        tasks.named('dependencyUpdates').configure {
          rejectVersionIf {
            candidate.version.startsWith('16.')
          }
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

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        configurations.maybeCreate('default')
        afterEvaluate {
          artifacts.add('default', file('child.jar'))
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.google.guava:guava:15.0'
        }
      """.stripIndent(),
    )
    testProjectDir.newFile('child/child.jar')

    when:
    def result = run('dependencyUpdates', '-DoutputFormatter=plain,json')
    def json = report('')

    then: "the child's unfiltered candidate is checked by the outer's rule, so the row stays up to date"
    result.task(':dependencyUpdates').outcome == SUCCESS
    json.outdated.dependencies.every { it.name != 'guava' }
    json.current.dependencies.find { it.name == 'guava' }?.version == '15.0'
    !result.output.contains('com.google.guava:guava [15.0 -> 16.0]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1004')
  def 'Warns of an aggregated coordinate that no included build is substituted for'() {
    given: 'the child is included only under pluginManagement, so it is not substituted'
    testProjectDir.newFile('settings.gradle') <<
      """
        pluginManagement {
          includeBuild 'child'
        }
      """.stripIndent()
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        dependencies {
          dependencyUpdatesAggregation 'com.example:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
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

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.example:jvm-library:1.0'
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then: "the coordinate is named, and the child's row is absent"
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      'Left out of the dependency updates report: com.example:child:1.0, which no included ' +
        'build was substituted for.')
    !result.output.contains('com.example:jvm-library [1.0 -> 2.0]')
  }

  def 'Warns of an aggregated coordinate that resolves to nothing'() {
    given: 'the coordinate misnames the group of the build that is included'
    testProjectDir.newFile('settings.gradle') << "includeBuild 'child'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          dependencyUpdatesAggregation 'com.exampl:child:1.0'
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
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

        apply plugin: 'io.github.ben-manes.versions'

        group = 'com.example'
        version = '1.0'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }

        dependencies {
          tool 'com.example:jvm-library:1.0'
        }
      """.stripIndent(),
    )

    when:
    def result = run('dependencyUpdates')

    then: "the coordinate is named, and the child's row is absent"
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains(
      'Left out of the dependency updates report: com.exampl:child:1.0, which no included build ' +
        'was substituted for. Check the coordinates against the group, name and version set in ' +
        'the included build.')
    !result.output.contains('com.example:jvm-library [1.0 -> 2.0]')
  }
}
