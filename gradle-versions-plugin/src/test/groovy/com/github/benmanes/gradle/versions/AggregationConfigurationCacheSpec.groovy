package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
final class AggregationConfigurationCacheSpec extends Specification {
  private static final List<String> ARGUMENTS =
    ['dependencyUpdates', '--no-parallel', '--configuration-cache']

  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File repository

  def 'setup'() {
    // A writable copy, so that a test may publish a new version between the builds.
    def source = new File(getClass().getResource('/maven/').toURI())
    repository = testProjectDir.newFolder('repository')
    source.eachFileRecurse { file ->
      def target = new File(repository, source.toPath().relativize(file.toPath()).toString())
      if (file.directory) {
        target.mkdirs()
      } else {
        target.parentFile.mkdirs()
        target.bytes = file.bytes
      }
    }

    testProjectDir.newFile('settings.gradle') << "include 'app', 'lib'"
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'io.github.ben-manes.versions'
        }

        allprojects {
          apply plugin: 'java'

          repositories {
            maven {
              url '${repository.toURI()}'
            }
          }
        }

        dependencyUpdates {
          checkForGradleUpdate = false
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
          testImplementation 'com.google.guava:guava:15.0'
          constraints {
            implementation 'com.google.inject.extensions:guice-multibindings:2.0'
          }
        }
      """.stripIndent()
  }

  private void configure(String settings) {
    new File(testProjectDir.root, 'build.gradle') <<
      """
        dependencyUpdates {
          ${settings}
        }
      """.stripIndent()
  }

  private void publishGuice(String version) {
    def module = new File(repository, 'com/google/inject/guice')
    def released = new File(module, version)
    released.mkdirs()
    new File(released, "guice-${version}.pom").text =
      new File(module, '3.1/guice-3.1.pom').text.replace('3.1', version)
    def metadata = new File(module, 'maven-metadata.xml')
    metadata.text = metadata.text
      .replace('<version>3.1</version>', "<version>3.1</version>\n      <version>${version}</version>")
      .replace('<latest>3.0</latest>', "<latest>${version}</latest>")
      .replace('<release>3.0</release>', "<release>${version}</release>")
  }

  private GradleRunner runner(List<String> arguments) {
    return GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
  }

  private def run(List<String> arguments) {
    return runner(arguments).build()
  }

  private def report() {
    return new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))
  }

  def 'Honors a setting inherited from an ancestor on the store and on the cache hit'() {
    // A cache hit does not run configuration, so the shared service holds no settings at all when
    // the task's inputs are replayed. The inherited values must therefore already be in the entry.
    // The cases above configure the root's own task, which reads back correctly even when nothing
    // is inherited, so only a subproject that configures none of its own covers this.
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        subprojects {
          apply plugin: 'io.github.ben-manes.versions'
        }

        tasks.dependencyUpdates {
          revision = 'release'
          checkConstraints = true
        }
      """.stripIndent()
    new File(testProjectDir.root, 'app/build.gradle') <<
      """
        dependencies {
          constraints {
            implementation 'com.google.guava:guava:15.0'
          }
        }
      """.stripIndent()

    when:
    def arguments = [':app:dependencyUpdates', '--no-parallel', '--configuration-cache']
    def store = run(arguments)
    def hit = run(arguments)

    then:
    store.task(':app:dependencyUpdates').outcome == SUCCESS
    hit.output.contains('Reusing configuration cache')
    // The header names the inherited revision, and the constrained guava is reported under the
    // inherited check, on the store and again on the replay.
    [store, hit].every { it.output.contains('The following dependencies have later release versions:') }
    [store, hit].every { !it.output.contains('The following dependencies have later milestone versions:') }
    [store, hit].every { it.output.contains('com.google.guava:guava') }
  }

  private List<String> candidatesOf(String projectPath) {
    def partials = new File(testProjectDir.root, 'build/dependencyUpdates/partials')
    def matched = partials.listFiles().collect { new JsonSlurper().parse(it) }
      .find { it.projectPath == projectPath }
    return matched.candidates as List<String>
  }

  @Unroll
  def 'Honors #hook on the store and on the cache hit'() {
    given:
    configure(settings)

    when:
    def store = run(ARGUMENTS)
    def hit = run(ARGUMENTS)

    then:
    store.task(':dependencyUpdates').outcome == SUCCESS
    hit.output.contains('Reusing configuration cache')
    // The producers resolve while the cache entry is stored, so a hit replays the frozen settings.
    present.every { store.output.contains(it) }
    present.every { hit.output.contains(it) }
    absent.every { !store.output.contains(it) }
    absent.every { !hit.output.contains(it) }

    where:
    hook << ['rejectVersionIf', 'resolutionStrategy', 'filterConfigurations', 'checkConstraints',
             'revision', 'filterDeclaredConfigurations', 'preReleaseVersionIf', 'exemptFromBuiltInChecksIf']
    settings << [
      '''
        rejectVersionIf {
          it.candidate.version == '3.1'
        }
      ''',
      '''
        resolutionStrategy {
          componentSelection {
            all { selection ->
              if (selection.candidate.version == '3.1') {
                selection.reject('rejected by the test')
              }
            }
          }
        }
      ''',
      '''
        filterConfigurations {
          !it.name.toLowerCase().contains('test')
        }
      ''',
      'checkConstraints = true',
      "revision = 'release'",
      '''
        project.configurations.create('pluginClasspath') {
          canBeResolved = true
          canBeConsumed = false
        }
        project.dependencies.add('pluginClasspath', 'org.apache.logging.log4j:log4j-core:2.16.0')
        filterDeclaredConfigurations {
          it != 'pluginClasspath'
        }
      ''',
      '''
        preReleaseVersionIf {
          it == '3.1'
        }
      ''',
      '''
        preReleaseVersionIf {
          it == '3.1'
        }
        exemptFromBuiltInChecksIf {
          candidate.module == 'guice'
        }
      ''',
    ]
    present << [
      ['com.google.inject:guice [2.0 -> 3.0]'],
      ['com.google.inject:guice [2.0 -> 3.0]'],
      ['com.google.inject:guice [2.0 -> 3.1]'],
      ['com.google.inject.extensions:guice-multibindings [2.0 -> 3.0]'],
      // The report names the revision and files the later version under it, so a hit that lost the
      // task's own settings would announce the default level and offer no version at all.
      ['The following dependencies have later release versions:',
       'com.google.inject:guice [2.0 -> 3.1]'],
      ['com.google.inject:guice [2.0 -> 3.1]'],
      // The convention marks 3.1 a pre-release, so it is the step after the release it holds back.
      ['com.google.inject:guice [2.0 -> 3.0 -> 3.1]'],
      ['com.google.inject:guice [2.0 -> 3.1]'],
    ]
    absent << [
      ['com.google.inject:guice [2.0 -> 3.1]'],
      ['com.google.inject:guice [2.0 -> 3.1]'],
      ['com.google.guava:guava'],
      [],
      ['The following dependencies have later milestone versions:'],
      ['org.apache.logging.log4j:log4j-core'],
      ['com.google.inject:guice [2.0 -> 3.1]\n'],
      ['com.google.inject:guice [2.0 -> 3.0]'],
    ]
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Keeps a rejectVersionIf that reads the build script out of the cache entry'() {
    given: 'a predicate reading a script object, which a serialized closure is not allowed to do'
    // Read before the version is compared, so that every candidate the report would replay the rule
    // over reaches it rather than short circuiting on the version the producer already rejected.
    configure(
      '''
        rejectVersionIf {
          project.path == ':' && it.candidate.version == '3.1'
        }
      ''')

    when:
    def store = run(ARGUMENTS)
    def hit = run(ARGUMENTS)

    then: 'a report that merges in no other build never serializes the action, so the read still works'
    store.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    hit.output.contains('Reusing configuration cache')
    hit.output.contains('com.google.inject:guice [2.0 -> 3.0]')
  }

  def 'Keeps an exemption from the built-in checks across the cache'() {
    given: 'a module with a pre-release as its only upgrade, exempted from the check that leaves it out'
    new File(testProjectDir.root, 'app/build.gradle') <<
      """
        dependencies {
          implementation 'com.example:prerelease-widget:1.0'
        }
      """.stripIndent()
    configure("exemptFromBuiltInChecksIf { candidate.module == 'prerelease-widget' }")

    when:
    def store = run(ARGUMENTS)
    def hit = run(ARGUMENTS)

    then: 'the report reads the exemption on both runs, as the producer that baked the row did'
    hit.output.contains('Reusing configuration cache')
    [store, hit].every { it.output.contains('com.example:prerelease-widget [1.0 -> 1.2-beta]') }
  }

  def 'Keeps a preReleaseVersionIf that matches the version in use across the cache'() {
    given: 'the convention covers the version in use, which is what keeps the upgrade in the report'
    new File(testProjectDir.root, 'app/build.gradle') <<
      """
        dependencies {
          implementation 'com.example:prerelease-widget:1.0'
        }
      """.stripIndent()
    configure("preReleaseVersionIf { it == '1.0' }")

    when:
    def store = run(ARGUMENTS)
    def hit = run(ARGUMENTS)

    then: 'a build already on a pre-release is shown the next one, in either cache mode'
    hit.output.contains('Reusing configuration cache')
    [store, hit].every { it.output.contains('com.example:prerelease-widget [1.0 -> 1.2-beta]') }
  }

  def 'Warns about assigning the resolutionStrategy only while storing the cache'() {
    given:
    configure(
      '''
        resolutionStrategy = {
          componentSelection {
            all { selection ->
              if (selection.candidate.version == '3.1') {
                selection.reject('rejected by the test')
              }
            }
          }
        }
      ''')

    when:
    def store = run(ARGUMENTS)
    def hit = run(ARGUMENTS)

    then:
    store.output.contains('Remove the assignment operator')
    // The warning is logged from afterEvaluate, which a cache hit skips, though the strategy was
    // already applied to the partial results that the hit replays.
    !hit.output.contains('Remove the assignment operator')
    store.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    hit.output.contains('com.google.inject:guice [2.0 -> 3.0]')
  }

  def 'Formats with a custom outputFormatter on the store and on the cache hit'() {
    given:
    configure(
      '''
        outputFormatter = { result ->
          println 'custom formatter outdated=' + result.outdated.dependencies.size()
        }
      ''')

    when:
    def store = run(ARGUMENTS)
    def hit = run(ARGUMENTS)

    then:
    store.output.contains('custom formatter outdated=2')
    hit.output.contains('Reusing configuration cache')
    hit.output.contains('custom formatter outdated=2')
    !hit.output.contains('The following dependencies have later milestone versions')
  }

  def 'Fails when a custom outputFormatter closure reads the build script'() {
    given:
    configure(
      '''
        outputFormatter = { result ->
          println project.path
        }
      ''')

    when:
    def store = runner(ARGUMENTS).buildAndFail()
    def hit = runner(ARGUMENTS).buildAndFail()

    then:
    // Gradle strips the owner, delegate and this of a serialized closure, so a custom formatter may
    // read only its result, its own locals and fully qualified types.
    store.output.contains('a Gradle script object from a Groovy closure')
    // Gradle 8 reuses the entry stored before the task failed, and Gradle 9 discards it and
    // calculates the task graph again, so every later build fails on the same formatter either way.
    hit.output.contains('a Gradle script object from a Groovy closure')
  }

  def 'Formats with a custom outputFormatter closure that captured a local'() {
    given:
    new File(testProjectDir.root, 'build.gradle') <<
      """
        def projectPath = project.path

        dependencyUpdates {
          outputFormatter = { result ->
            println 'captured path=' + projectPath + ' outdated=' + result.outdated.dependencies.size()
          }
        }
      """.stripIndent()

    when:
    def store = run(ARGUMENTS)
    def hit = run(ARGUMENTS)

    then:
    // A local is serialized with the closure, unlike the script object it was read from, so this is
    // the supported way to get a build script value into a custom formatter.
    store.output.contains('captured path=: outdated=2')
    hit.output.contains('Reusing configuration cache')
    hit.output.contains('captured path=: outdated=2')
  }

  def 'Resolves the project url while storing the cache'() {
    when:
    def store = run(ARGUMENTS)
    def hit = run(ARGUMENTS)

    then:
    // The pom is read by an artifact query as the producer resolves, which happens while the cache
    // entry is stored rather than while the task runs, so a url in the report proves the query is
    // usable there and that its answer is carried into a hit.
    store.output.contains('https://code.google.com/p/google-guice/')
    hit.output.contains('Reusing configuration cache')
    hit.output.contains('https://code.google.com/p/google-guice/')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/801')
  def 'Reports the skipped configurations on the store and on the cache hit'() {
    given:
    // The measured #801 trigger: a withModule id missing its ':name' half.
    configure(
      '''
        resolutionStrategy {
          componentSelection { rules ->
            rules.withModule('com.google.guava') { }
          }
        }
      ''')

    when:
    def store = run(ARGUMENTS + ['-DoutputFormatter=plain,json'])
    def stored = report()

    then:
    store.task(':dependencyUpdates').outcome == SUCCESS
    store.output.contains('Skipping configuration')
    store.output.contains('Failed to inspect the dependencies of the following configurations')
    stored.skipped.count > 0
    stored.skipped.configurations*.name.contains('compileClasspath')

    when:
    def hit = run(ARGUMENTS + ['-DoutputFormatter=plain,json'])

    then:
    hit.output.contains('Reusing configuration cache')
    // A hit does not re-evaluate the producers, so the warning they log while resolving does not
    // re-emit, while the report the task writes from the replayed results is unchanged.
    hit.output.contains('Failed to inspect the dependencies of the following configurations')
    report() == stored
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Records the complete candidate listing on the store, verdict and facts together, and replays it on the cache hit'() {
    given:
    configure(
      '''
        rejectVersionIf {
          it.candidate.version == '3.1'
        }
      ''')

    when:
    def store = run(ARGUMENTS)
    def stored = candidatesOf(':app')

    then:
    store.task(':app:partialDependencyUpdates').outcome == SUCCESS
    // Every listed version is recorded as a fact, including the one the build's own rejectVersionIf
    // rejects, while the verdict the report shows is still the first-accept walk's choice.
    stored as Set == ['com.google.inject:guice:3.1', 'com.google.inject:guice:3.0',
                       'com.google.inject:guice:2.2', 'com.google.inject:guice:2.1',
                       'com.google.inject:guice:2.0', 'com.google.inject:guice:1.0'] as Set
    store.output.contains('com.google.inject:guice [2.0 -> 3.0]')

    when:
    new File(testProjectDir.root, 'build/dependencyUpdates/partials').deleteDir()
    // The deletion is made from this JVM rather than by the build, so the daemon's virtual file
    // system can miss it and report the producers up to date against a partial that is gone.
    def hit = run(ARGUMENTS + '--no-watch-fs')

    then:
    // The recorder runs inside the component-selection rule while the producer's task input is
    // realized, which happens during the store rather than the hit, so a value that survives the
    // hit proves it travelled through the frozen task input rather than being recomputed. Complete
    // recording is 100x the payload of the prefix R60.1 shipped, so this also proves that scale
    // survives the cache.
    hit.output.contains('Reusing configuration cache')
    hit.task(':app:partialDependencyUpdates').outcome == SUCCESS
    candidatesOf(':app') as Set == stored as Set
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Records nothing for a module reachable only through a configuration filterConfigurations rejects'() {
    given:
    configure(
      '''
        filterConfigurations {
          !it.name.toLowerCase().contains('test')
        }
      ''')

    when:
    def result = run(ARGUMENTS)

    then:
    result.task(':lib:partialDependencyUpdates').outcome == SUCCESS
    // guava is declared on lib's testImplementation, which the filter excludes before a
    // configuration is ever handed to the resolver.
    !candidatesOf(':lib').any { it.startsWith('com.google.guava:') }
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Does not duplicate a candidate recorded by both the project and buildscript passes'() {
    given:
    configure(
      '''
        rejectVersionIf {
          it.candidate.version == '3.1'
        }
      ''')
    // The same module reachable from both the project's own configuration and its buildscript
    // classpath, so each of its candidates is a target for both statusesOf passes.
    new File(testProjectDir.root, 'app/build.gradle') <<
      """
        buildscript {
          repositories {
            maven {
              url '${repository.toURI()}'
            }
          }
          dependencies {
            classpath('com.google.inject:guice:2.0') {
              transitive = false
            }
          }
        }
      """.stripIndent()

    when:
    def result = run(ARGUMENTS)
    def recorded = candidatesOf(':app')

    then:
    result.task(':app:partialDependencyUpdates').outcome == SUCCESS
    recorded.count { it == 'com.google.inject:guice:3.1' } == 1
    recorded.count { it == 'com.google.inject:guice:3.0' } == 1
  }

  def 'Invalidates the cache when a dependency publishes a new version'() {
    when:
    def store = run(ARGUMENTS)
    def hit = run(ARGUMENTS)
    publishGuice('3.2')
    def republished = run(ARGUMENTS)

    then:
    store.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    hit.output.contains('Reusing configuration cache')
    hit.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    // The metadata read while the producer resolves is a cache input, so no stale result is served.
    !republished.output.contains('Reusing configuration cache')
    republished.output.contains('com.google.inject:guice [2.0 -> 3.2]')
  }
}
