package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Unroll

// Gradle 9 requires JVM 17.
@Requires({ jvm.java17Compatible })
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/666')
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/533')
final class SettingsPluginAggregationSpec extends Specification {
  private static final List<String> ISOLATED =
    ['-Dorg.gradle.isolated-projects=true', '--configuration-cache']

  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
    testProjectDir.newFolder('app')
    testProjectDir.newFolder('lib')
  }

  /** Writes the build, applying the versions plugin to the named projects. */
  private void createBuild(List<String> appliedTo) {
    write('build.gradle', appliedTo.contains(':'), null)
    write('app/build.gradle', appliedTo.contains(':app'), 'com.google.inject:guice:2.0')
    write('lib/build.gradle', appliedTo.contains(':lib'), 'com.google.guava:guava:15.0')
  }

  private void write(String path, boolean applied, String dependency) {
    def plugins = dependency == null ? [] : ['java']
    if (applied) {
      plugins += 'io.github.ben-manes.versions'
    }
    new File(testProjectDir.root, path).text =
      """
        plugins {
          ${plugins.collect { "id '${it}'" }.join('\n  ')}
        }
      """.stripIndent()
    if (dependency != null) {
      new File(testProjectDir.root, path) <<
        """
          repositories {
            maven {
              url = '${mavenRepoUrl}'
            }
          }

          dependencies {
            implementation '${dependency}'
          }
        """.stripIndent()
    }
  }

  private void settingsApplying(boolean applied) {
    new File(testProjectDir.root, 'settings.gradle').text =
      (applied ? "plugins {\n  id 'io.github.ben-manes.versions.settings'\n}\n\n" : '') +
        "include 'app', 'lib'\n"
  }

  private def run(List<String> arguments) {
    return runWith([':dependencyUpdates'] + arguments)
  }

  private def runWith(List<String> arguments) {
    return GradleRunner.create()
      .withGradleVersion('9.7.0')
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .withPluginClasspath()
      .build()
  }

  def 'Aggregates every project when applied once from settings with isolated projects'() {
    given:
    createBuild([])
    settingsApplying(true)

    when:
    def result = run(ISOLATED)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    // The project plugin cannot register a producer in a project that does not apply it, so a
    // root-only project application omits the subprojects and warns. Applying from the settings
    // reaches every project, so the report is complete without asking the user to.
    !result.output.contains('The dependency updates report is missing')
  }

  def 'Reports the latest versions on a configuration cache hit with isolated projects'() {
    given:
    createBuild([])
    settingsApplying(true)

    when:
    def stored = run(ISOLATED + ['--parallel'])

    then:
    stored.task(':dependencyUpdates').outcome == SUCCESS
    stored.output.contains('Configuration cache entry stored')

    when:
    def hit = run(ISOLATED + ['--parallel'])

    then:
    hit.task(':dependencyUpdates').outcome == SUCCESS
    hit.output.contains('Configuration cache entry reused')
    hit.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    hit.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
  }

  def 'Reports the same as applying the plugin to each project'() {
    given:
    createBuild([':', ':app', ':lib'])
    settingsApplying(false)

    when:
    run(['-DoutputFormatter=json'])
    def fromProjects = report()

    then:
    fromProjects.contains('com.google.inject')
    fromProjects.contains('com.google.guava')

    when:
    createBuild([])
    settingsApplying(true)
    new File(testProjectDir.root, 'build/dependencyUpdates/report.json').delete()
    run(['-DoutputFormatter=json'])
    def fromSettings = report()

    then:
    fromSettings == fromProjects
  }

  def 'Reports once from the root when invoked by name'() {
    given:
    createBuild([])
    settingsApplying(true)

    when:
    def result = runWith(['dependencyUpdates'] + ISOLATED)

    then:
    // Only the root has the reporting task, so the bare name runs one task and one merged report.
    result.tasks.findAll { it.path.endsWith(':dependencyUpdates') }*.path == [':dependencyUpdates']
    result.output.count('com.google.inject:guice [2.0 -> 3.1]') == 1
    !result.output.contains('The dependency updates report is missing')
  }

  @Unroll
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1006')
  def 'Aggregates every project with configure on demand when invoked #invocation'() {
    given:
    createBuild([])
    settingsApplying(true)
    // An included build computes the task graph while the aggregate's configuration is still being
    // set up, which is what configure on demand then resolves too early. Single threaded, which a
    // root-only application survives: every project has a producer here, so there is no ordering
    // left that hides it.
    testProjectDir.newFolder('included')
    new File(testProjectDir.root, 'included/settings.gradle').text = ''
    new File(testProjectDir.root, 'included/build.gradle').text = ''
    new File(testProjectDir.root, 'settings.gradle') << "includeBuild 'included'\n"

    when:
    def result = runWith([invocation, '--configure-on-demand', '--no-parallel'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    !result.output.contains('The dependency updates report is missing')

    where:
    invocation << [':dependencyUpdates', 'dependencyUpdates']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1040')
  def 'Collects the partial results under the root with isolated projects'() {
    given:
    createBuild([])
    new File(testProjectDir.root, 'settings.gradle').text =
      """
        plugins {
          id 'io.github.ben-manes.versions.settings'
        }

        include 'app', 'lib', 'container:nested'

        gradle.lifecycle.beforeProject { project ->
          if (project.path == ':container') {
            project.pluginManager.apply('java')
            project.repositories.maven { url = '${mavenRepoUrl}' }
            project.dependencies.add('implementation', 'com.example:jvm-library:1.0')
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('container', 'nested')
    write('container/nested/build.gradle', false, 'com.google.inject:guice:2.0')

    when:
    def result = run(ISOLATED)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    // Guards the flag itself: the property is a silent no-op on a Gradle whose spelling differs
    // (pre-9.7 uses org.gradle.unsafe.isolated-projects), which would pass the non-isolated branch.
    result.output.contains('Isolated Projects is an incubating feature.')
    // A project that exists only to hold a nested include has no build script to apply a plugin
    // from, so the settings plugin reaches it and an earlier release gave it a build directory to
    // hold the partial result. The producer writes under the aggregating project instead.
    !new File(testProjectDir.root, 'container/build').exists()
    new File(testProjectDir.root, 'build/dependencyUpdates/partials').list().length == 5
    // The container is still resolved, as the settings script can carry a dependency into a project
    // that has no build script of its own.
    result.output.contains('com.example:jvm-library [1.0 -> 2.0]')

    when:
    def hit = run(ISOLATED)

    then:
    hit.output.contains('Configuration cache entry reused')
    // The destination is realized before the entry is stored, so a hit writes where the store did.
    !new File(testProjectDir.root, 'container/build').exists()
    hit.output.contains('com.example:jvm-library [1.0 -> 2.0]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1040')
  def 'Removes what an earlier release wrote into each project with isolated projects'() {
    given:
    createBuild([])
    settingsApplying(true)
    new File(testProjectDir.root, 'settings.gradle') << "include 'container:nested'\n"
    testProjectDir.newFolder('container', 'nested')
    write('container/nested/build.gradle', false, 'com.google.inject:guice:2.0')
    // What a release before the results were collected under one directory left in each project.
    def legacies = ['', 'app/', 'lib/', 'container/'].collect {
      new File(testProjectDir.root, "${it}build/dependencyUpdates/partial.json")
    }
    legacies.each { it.parentFile.mkdirs(); it.text = '{}' }

    when:
    def result = run(ISOLATED + ['--clean-legacy-partials'])

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('Isolated Projects is an incubating feature.')
    // A project with no build script has no clean task to reach it, as clean comes from the base
    // plugin, so the opt in cleanup is the only thing that can.
    legacies.every { !it.exists() }
    !new File(testProjectDir.root, 'container/build').exists()

    when:
    legacies.each { it.parentFile.mkdirs(); it.text = '{}' }
    def hit = run(ISOLATED + ['--clean-legacy-partials'])

    then:
    hit.output.contains('Configuration cache entry reused')
    // The paths are realized before the entry is stored, as a hit configures no project to publish
    // them again.
    legacies.every { !it.exists() }
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1040')
  def 'Keeps the partial result of a project that conflict resolution drops'() {
    given:
    new File(testProjectDir.root, 'settings.gradle').text =
      """
        plugins {
          id 'io.github.ben-manes.versions.settings'
        }

        include 'core:api', 'feature:api'

        gradle.lifecycle.beforeProject { project ->
          project.group = 'com.example'
        }
      """.stripIndent()
    testProjectDir.newFolder('core', 'api')
    testProjectDir.newFolder('feature', 'api')
    write('build.gradle', false, null)
    write('core/api/build.gradle', false, 'com.google.guava:guava:15.0')
    write('feature/api/build.gradle', true, 'com.google.inject:guice:2.0')

    when:
    def own = runWith([':feature:api:dependencyUpdates'] + ISOLATED)
    def partial = new File(testProjectDir.root, 'build/dependencyUpdates/partials')
      .listFiles().find { it.name.startsWith('feature-api-') }

    then:
    own.task(':feature:api:dependencyUpdates').outcome == SUCCESS
    own.output.contains('Isolated Projects is an incubating feature.')
    partial != null

    when:
    def root = runWith([':dependencyUpdates'] + ISOLATED)

    then:
    root.task(':dependencyUpdates').outcome == SUCCESS
    // Module conflict resolution aggregates two projects that share a group and name as one, so the
    // artifacts name fewer projects than the build has producers. The results of the other project
    // are still its own, and its report reads them from where it wrote them.
    partial.exists()
  }

  @Issue([
    'https://github.com/ben-manes/gradle-versions-plugin/issues/1022',
    'https://github.com/ben-manes/gradle-versions-plugin/issues/1046',
  ])
  def 'Aggregates a project that publishes through its default configuration'() {
    given:
    createBuild([])
    settingsApplying(true)
    new File(testProjectDir.root, 'settings.gradle') << "include 'local'\n"
    testProjectDir.newFolder('local')
    testProjectDir.newFile('local/local.jar')
    // Declares no variant of its own, so that a consumer resolves it through the fallback to its
    // default configuration, as a project that exposes a local aar or jar file does. The reported
    // dependency is declared on a plain resolvable configuration rather than through the `java`
    // plugin, which would give the project variants of its own and void the case.
    new File(testProjectDir.root, 'local/build.gradle').text =
      """
        configurations.maybeCreate('default')
        artifacts.add('default', file('local.jar'))

        repositories {
          maven {
            url = '${mavenRepoUrl}'
          }
        }

        configurations.create('tool') {
          canBeResolved = true
          canBeConsumed = false
        }
        dependencies {
          tool 'com.example:jvm-library:1.0'
        }
      """.stripIndent()
    // Resolved on its own configuration rather than the runtime classpath, which also carries the
    // external dependency this build reports on, whose poms the test repository publishes without
    // the files to resolve them to.
    new File(testProjectDir.root, 'app/build.gradle') <<
      """
        configurations.create('local') {
          canBeResolved = true
          canBeConsumed = false
        }
        dependencies {
          local project(':local')
        }

        tasks.register('resolve', Copy) {
          from configurations.local
          into layout.buildDirectory.dir('resolved')
        }
      """.stripIndent()

    when:
    def result = run(ISOLATED)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.example:jvm-library [1.0 -> 2.0]')
    !result.output.contains('The dependency updates report is missing')

    when: 'a consumer resolves the project the statuses were published from'
    def consumed = runWith([':app:resolve'] + ISOLATED)

    then: 'the unattributed publication left its fallback to the default configuration intact'
    consumed.task(':app:resolve').outcome == SUCCESS
    new File(testProjectDir.root, 'app/build/resolved/local.jar').exists()
  }

  @Issue([
    'https://github.com/ben-manes/gradle-versions-plugin/issues/1022',
    'https://github.com/ben-manes/gradle-versions-plugin/issues/1047',
  ])
  def 'Consumes a project that publishes through its default configuration from afterEvaluate'() {
    given:
    createBuild([])
    settingsApplying(true)
    new File(testProjectDir.root, 'settings.gradle') << "include 'late'\n"
    testProjectDir.newFolder('late')
    testProjectDir.newFile('late/late.jar')
    // Puts its artifact on the default configuration from its own afterEvaluate, as a plugin that
    // declares its publication there does. The settings plugin reaches the project before its build
    // script runs, so this callback is registered after the one that publishes the statuses.
    new File(testProjectDir.root, 'late/build.gradle').text =
      """
        configurations.maybeCreate('default')
        afterEvaluate {
          artifacts.add('default', file('late.jar'))
        }
      """.stripIndent()
    new File(testProjectDir.root, 'app/build.gradle') <<
      """
        configurations.create('late') {
          canBeResolved = true
          canBeConsumed = false
        }
        dependencies {
          late project(':late')
        }

        tasks.register('resolveLate', Copy) {
          from configurations.late
          into layout.buildDirectory.dir('late')
        }

      """.stripIndent()

    when:
    def consumed = runWith([':app:resolveLate'] + ISOLATED)

    then: 'the statuses did not cost the project the fallback its consumers resolve through'
    consumed.task(':app:resolveLate').outcome == SUCCESS
    new File(testProjectDir.root, 'app/build/late/late.jar').exists()
    // Served in the artifact's place before this was left out of variant selection, rather than
    // failing, so the consumer took the statuses onto its classpath and carried on.
    !new File(testProjectDir.root, 'app/build/late/partial.json').exists()
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/801')
  def 'Surfaces every projects skipped configurations when applied from settings'() {
    given:
    createBuild([])
    settingsApplying(true)
    // The measured #801 trigger: a withModule id missing its ':name' half. Declared on the root
    // task, which is the only one the settings application registers, so it reaches the producer of
    // every project rather than only the one it was written in.
    new File(testProjectDir.root, 'build.gradle') <<
      '''
        dependencyUpdates.resolutionStrategy {
          componentSelection { rules ->
            rules.withModule('com.google.guava') { }
          }
        }
      '''.stripIndent()

    when:
    def result = run(ISOLATED + ['-DoutputFormatter=plain,json'])
    def json = new JsonSlurper()
      .parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.json'))

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('Isolated Projects is an incubating feature.')
    result.output.contains('Failed to inspect the dependencies of the following configurations')
    json.skipped.configurations*.project.toUnique().toSorted() == [':', ':app', ':lib']
    json.skipped.configurations*.name.contains('compileClasspath')
  }

  def 'Applies once when a project also applies the plugin itself'() {
    given:
    createBuild([':app'])
    settingsApplying(true)

    when:
    def result = run(ISOLATED)

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('com.google.guava:guava [15.0 -> 16.0-rc1]')
    !result.output.contains('The dependency updates report is missing')
    // A second producer for the project would report its modules twice.
    result.output.count('com.google.inject:guice [2.0 -> 3.1]') == 1
  }

  private String report() {
    return new File(testProjectDir.root, 'build/dependencyUpdates/report.json').text
  }
}
