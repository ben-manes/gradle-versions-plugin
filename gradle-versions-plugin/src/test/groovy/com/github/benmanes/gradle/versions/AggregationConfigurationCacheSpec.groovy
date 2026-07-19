package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
final class AggregationConfigurationCacheSpec extends Specification {
  private static final List<String> ARGUMENTS =
    ['dependencyUpdates', '--no-parallel', '-Dcom.github.benmanes.versions.aggregate=true',
     '--configuration-cache']

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
          id 'com.github.ben-manes.versions'
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
             'revision']
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
    ]
    absent << [
      ['com.google.inject:guice [2.0 -> 3.1]'],
      ['com.google.inject:guice [2.0 -> 3.1]'],
      ['com.google.guava:guava'],
      [],
      ['The following dependencies have later milestone versions:'],
    ]
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
    store.output.contains('Cannot reference a Gradle script object from a Groovy closure')
    // The entry is stored before the task fails, so every later build reuses the failing formatter.
    hit.output.contains('Reusing configuration cache')
    hit.output.contains('Cannot reference a Gradle script object from a Groovy closure')
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
