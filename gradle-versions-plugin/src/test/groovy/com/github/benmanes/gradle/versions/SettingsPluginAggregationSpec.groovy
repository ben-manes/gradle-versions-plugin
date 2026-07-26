package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

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
      .withGradleVersion('9.7.0-rc-1')
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
